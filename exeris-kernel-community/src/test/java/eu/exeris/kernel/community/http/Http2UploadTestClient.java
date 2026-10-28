/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.http;

import eu.exeris.kernel.community.memory.CommunityMemoryProvider;
import eu.exeris.kernel.core.http.hpack.HpackDecoder;
import eu.exeris.kernel.core.http.hpack.HpackDynamicTable;
import eu.exeris.kernel.spi.memory.MemoryAllocator;
import eu.exeris.kernel.spi.memory.MemoryProviderConfig;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.foreign.MemorySegment;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * A minimal HTTP/2 client that uploads one request body and reads the response, sending only what the
 * server's flow-control windows allow. The body goes on stream 1, or on stream 3 after an {@code h2c}
 * upgrade, where stream 1 carries the upgrade request.
 *
 * <p>It tracks the connection window and the stream window from the server's WINDOW_UPDATE frames, never
 * sends a DATA frame larger than either, and when either is zero it waits on a monitor — woken by the
 * reader thread, bounded by a deadline — instead of sending anyway. A server that never returns credit
 * therefore leaves the upload stalled until the deadline, and {@link #upload} fails there, where a
 * client that over-sent would have been served and hidden the defect.
 *
 * <p>The reader runs on a platform thread: blocking socket reads can pin a carrier, and the test JVM
 * forces only two virtual-thread carriers. Writes are serialised by one lock because the reader
 * answers SETTINGS and returns response credit while the test thread may be writing.
 */
final class Http2UploadTestClient implements AutoCloseable {

    private static final long WAIT_MILLIS = 15_000L;
    private static final long CONNECT_TIMEOUT_MILLIS = 5_000L;
    private static final int INITIAL_WINDOW = 65_535;
    private static final int MAX_FRAME = 16_384;
    private static final int FIRST_CLIENT_STREAM = 1;
    private static final int STREAM_AFTER_UPGRADE = 3;
    private static final int TYPE_DATA = 0x0;
    private static final int TYPE_HEADERS = 0x1;
    private static final int TYPE_RST_STREAM = 0x3;
    private static final int TYPE_SETTINGS = 0x4;
    private static final int TYPE_PING = 0x6;
    private static final int TYPE_GOAWAY = 0x7;
    private static final int TYPE_WINDOW_UPDATE = 0x8;
    private static final int FLAG_END_STREAM = 0x1;
    private static final int FLAG_END_HEADERS = 0x4;
    private static final int FLAG_ACK = 0x1;
    private static final int STATIC_METHOD_POST = 3;
    private static final int STATIC_PATH = 4;
    private static final int STATIC_SCHEME_HTTP = 6;
    private static final int STATIC_SCHEME_HTTPS = 7;
    private static final int STATIC_AUTHORITY = 1;
    private static final byte[] PREFACE =
            "PRI * HTTP/2.0\r\n\r\nSM\r\n\r\n".getBytes(StandardCharsets.US_ASCII);
    private static final MemoryAllocator ALLOCATOR =
            new CommunityMemoryProvider().createAllocator(MemoryProviderConfig.defaults());

    private final Socket socket;
    private final int streamId;
    private final Object writeLock = new Object();
    private final Object monitor = new Object();
    private final HpackDecoder decoder = new HpackDecoder(new HpackDynamicTable(4096), ALLOCATOR, 65_536, 65_536);
    private final ByteArrayOutputStream responseBody = new ByteArrayOutputStream();
    // The fields below are guarded by monitor.
    private long connectionWindow = INITIAL_WINDOW;
    private long streamWindow = INITIAL_WINDOW;
    private boolean responseEnded;
    private boolean connectionEnded;
    private int resetCode = -1;
    private String status;
    private boolean goAway;
    private Thread reader;

    private Http2UploadTestClient(Socket socket, int streamId) {
        this.socket = socket;
        this.streamId = streamId;
    }

    /**
     * Connects in cleartext with prior knowledge and opens the request stream.
     *
     * @param port the server's port on the loopback
     * @param path the request path
     * @return a client that is already reading
     */
    static Http2UploadTestClient connectPlain(int port, String path) {
        try {
            Socket socket = new Socket();
            socket.connect(new InetSocketAddress("127.0.0.1", port), (int) CONNECT_TIMEOUT_MILLIS);
            socket.setTcpNoDelay(true);
            return begin(socket, path, "http", FIRST_CLIENT_STREAM);
        } catch (IOException ex) {
            throw new IllegalStateException("Unable to connect the HTTP/2 upload client", ex);
        }
    }

    /**
     * Connects in cleartext, upgrades with {@code Upgrade: h2c} on a bodiless {@code GET path}, reads the
     * {@code 101}, and then opens the request stream on stream 3, as a client that upgrades does.
     *
     * @param port the server's port on the loopback
     * @param path the path of both the upgrade request and the upload
     * @return a client that is already reading
     */
    static Http2UploadTestClient connectUpgrade(int port, String path) {
        try {
            Socket socket = new Socket();
            socket.connect(new InetSocketAddress("127.0.0.1", port), (int) CONNECT_TIMEOUT_MILLIS);
            socket.setTcpNoDelay(true);
            socket.setSoTimeout((int) WAIT_MILLIS);
            OutputStream out = socket.getOutputStream();
            out.write(("GET " + path + " HTTP/1.1\r\nHost: 127.0.0.1\r\nConnection: Upgrade\r\n"
                    + "Upgrade: h2c\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
            out.flush();
            String head = readResponseHead(socket);
            if (!head.startsWith("HTTP/1.1 101")) {
                socket.close();
                throw new IllegalStateException("the server did not switch protocols: " + head);
            }
            socket.setSoTimeout(0);
            return begin(socket, path, "http", STREAM_AFTER_UPGRADE);
        } catch (IOException ex) {
            throw new IllegalStateException("Unable to connect the h2c upgrade upload client", ex);
        }
    }

    /** Reads byte by byte up to the blank line, so no HTTP/2 byte after the head is consumed. */
    private static String readResponseHead(Socket socket) throws IOException {
        ByteArrayOutputStream head = new ByteArrayOutputStream();
        InputStream in = socket.getInputStream();
        int matched = 0;
        while (matched < 4) {
            int next = in.read();
            if (next < 0) {
                throw new IOException("the connection ended inside the response head");
            }
            head.write(next);
            matched = next == "\r\n\r\n".charAt(matched) ? matched + 1 : (next == '\r' ? 1 : 0);
        }
        return head.toString(StandardCharsets.US_ASCII);
    }

    /**
     * Connects over TLS with ALPN {@code h2}, requires the server to select it, and opens the request
     * stream.
     *
     * @param port  the server's port on the loopback
     * @param trust the context that trusts the server's certificate
     * @param path  the request path
     * @return a client that is already reading
     */
    static Http2UploadTestClient connectTls(int port, SSLContext trust, String path) {
        try {
            SSLSocket socket = (SSLSocket) trust.getSocketFactory().createSocket();
            socket.connect(new InetSocketAddress("127.0.0.1", port), (int) CONNECT_TIMEOUT_MILLIS);
            SSLParameters parameters = socket.getSSLParameters();
            parameters.setApplicationProtocols(new String[] {"h2"});
            socket.setSSLParameters(parameters);
            socket.startHandshake();
            if (!"h2".equals(socket.getApplicationProtocol())) {
                socket.close();
                throw new IllegalStateException("the server did not select h2 through ALPN: "
                        + socket.getApplicationProtocol());
            }
            return begin(socket, path, "https", FIRST_CLIENT_STREAM);
        } catch (IOException ex) {
            throw new IllegalStateException("Unable to connect the HTTP/2 upload TLS client", ex);
        }
    }

    private static Http2UploadTestClient begin(Socket socket, String path, String scheme, int streamId)
            throws IOException {
        Http2UploadTestClient client = new Http2UploadTestClient(socket, streamId);
        OutputStream out = socket.getOutputStream();
        synchronized (client.writeLock) {
            out.write(PREFACE);
            writeFrame(out, TYPE_SETTINGS, 0, 0, new byte[0]);
            writeFrame(out, TYPE_HEADERS, FLAG_END_HEADERS, streamId, requestBlock(scheme, path));
            out.flush();
        }
        client.reader = Thread.ofPlatform().daemon(true).name("h2-upload-client")
                .start(client::readLoop);
        return client;
    }

    /**
     * Sends {@code body} as the request body, one DATA frame at a time, each no larger than the
     * connection and stream windows the server has granted.
     *
     * @param body the request body
     * @throws AssertionError when a window stays zero for the wait bound, or the connection ends
     */
    void upload(byte[] body) {
        int sent = 0;
        try {
            OutputStream out = socket.getOutputStream();
            while (sent < body.length) {
                int length = reserveWindow(body.length - sent, sent, body.length);
                byte[] chunk = new byte[length];
                System.arraycopy(body, sent, chunk, 0, length);
                sent += length;
                write(out, TYPE_DATA, sent == body.length ? FLAG_END_STREAM : 0, streamId, chunk);
            }
        } catch (IOException ex) {
            throw new AssertionError("the connection failed after " + sent + " of " + body.length
                    + " body bytes", ex);
        }
    }

    /** Takes up to one frame from both windows, waiting while either is zero. */
    private int reserveWindow(int remaining, int sent, int total) {
        long deadline = System.nanoTime() + WAIT_MILLIS * 1_000_000L;
        synchronized (monitor) {
            while (Math.min(connectionWindow, streamWindow) <= 0) {
                if (connectionEnded || resetCode >= 0) {
                    throw new AssertionError("the exchange ended after " + sent + " of " + total
                            + " body bytes: reset=" + resetCode + " goAway=" + goAway);
                }
                long left = deadline - System.nanoTime();
                if (left <= 0) {
                    throw new AssertionError("stalled with a flow-control window of zero after " + sent
                            + " of " + total + " body bytes: connection=" + connectionWindow
                            + " stream=" + streamWindow);
                }
                try {
                    monitor.wait(left / 1_000_000L, (int) (left % 1_000_000L));
                } catch (InterruptedException _) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError("interrupted while waiting for flow-control credit");
                }
            }
            int length = (int) Math.min(Math.min(remaining, MAX_FRAME), Math.min(connectionWindow, streamWindow));
            connectionWindow -= length;
            streamWindow -= length;
            return length;
        }
    }

    /**
     * Waits for the response to end.
     *
     * @return the response body as text
     * @throws AssertionError when no complete response arrives within the wait bound
     */
    String awaitResponseBody() {
        long deadline = System.nanoTime() + WAIT_MILLIS * 1_000_000L;
        synchronized (monitor) {
            while (!responseEnded) {
                long left = deadline - System.nanoTime();
                if (left <= 0 || connectionEnded || resetCode >= 0) {
                    throw new AssertionError("no complete response: reset=" + resetCode + " goAway=" + goAway
                            + " connectionEnded=" + connectionEnded);
                }
                try {
                    monitor.wait(left / 1_000_000L, (int) (left % 1_000_000L));
                } catch (InterruptedException _) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError("interrupted while waiting for the response");
                }
            }
            return responseBody.toString(StandardCharsets.UTF_8);
        }
    }

    /**
     * Returns the {@code :status} of the response head.
     *
     * @return the status, or {@code null} when no head has arrived
     */
    String status() {
        synchronized (monitor) {
            return status;
        }
    }

    /**
     * Reports whether the server sent GOAWAY.
     *
     * @return {@code true} once a GOAWAY has been read
     */
    boolean goAwayReceived() {
        synchronized (monitor) {
            return goAway;
        }
    }

    @SuppressWarnings("PMD.AvoidCatchingGenericException")
    private void readLoop() {
        try {
            DataInputStream in = new DataInputStream(socket.getInputStream());
            OutputStream out = socket.getOutputStream();
            while (true) {
                int lengthAndType = in.readInt();
                int length = lengthAndType >>> 8;
                int type = lengthAndType & 0xFF;
                int flags = in.readUnsignedByte();
                int frameStreamId = in.readInt() & 0x7FFF_FFFF;
                byte[] payload = in.readNBytes(length);
                if (payload.length != length) {
                    return;
                }
                handle(out, type, flags, frameStreamId, payload);
            }
        } catch (IOException | RuntimeException _) {
            // Disconnect or close: the waiters see connectionEnded.
        } finally {
            synchronized (monitor) {
                connectionEnded = true;
                monitor.notifyAll();
            }
        }
    }

    private void handle(OutputStream out, int type, int flags, int frameStreamId, byte[] payload) throws IOException {
        if (type == TYPE_SETTINGS && (flags & FLAG_ACK) == 0) {
            write(out, TYPE_SETTINGS, FLAG_ACK, 0, new byte[0]);
        } else if (type == TYPE_PING && (flags & FLAG_ACK) == 0) {
            write(out, TYPE_PING, FLAG_ACK, 0, payload);
        } else if (type == TYPE_GOAWAY) {
            synchronized (monitor) {
                goAway = true;
                monitor.notifyAll();
            }
        } else if (type == TYPE_WINDOW_UPDATE) {
            int increment = ((payload[0] & 0x7F) << 24) | ((payload[1] & 0xFF) << 16)
                    | ((payload[2] & 0xFF) << 8) | (payload[3] & 0xFF);
            synchronized (monitor) {
                if (frameStreamId == 0) {
                    connectionWindow += increment;
                } else if (frameStreamId == streamId) {
                    streamWindow += increment;
                }
                monitor.notifyAll();
            }
        } else if (type == TYPE_RST_STREAM && frameStreamId == streamId) {
            synchronized (monitor) {
                resetCode = ((payload[0] & 0xFF) << 24) | ((payload[1] & 0xFF) << 16)
                        | ((payload[2] & 0xFF) << 8) | (payload[3] & 0xFF);
                monitor.notifyAll();
            }
        } else if (type == TYPE_HEADERS) {
            onHeaders(frameStreamId, payload);
        } else if (type == TYPE_DATA && frameStreamId == streamId) {
            onData(out, flags, payload);
        }
    }

    /**
     * Decodes every header block, whichever stream it is on: the HPACK dynamic table is per connection,
     * so skipping one block leaves the table behind the server's and a later block misdecodes.
     */
    private void onHeaders(int frameStreamId, byte[] payload) {
        List<Map.Entry<String, String>> fields = new ArrayList<>();
        decoder.decode(MemorySegment.ofArray(payload), 0, payload.length,
                (name, value, sensitive) -> fields.add(Map.entry(name, value)));
        if (frameStreamId != streamId) {
            return;
        }
        synchronized (monitor) {
            for (Map.Entry<String, String> field : fields) {
                if (":status".equals(field.getKey())) {
                    status = field.getValue();
                }
            }
            monitor.notifyAll();
        }
    }

    private void onData(OutputStream out, int flags, byte[] payload) throws IOException {
        synchronized (monitor) {
            responseBody.writeBytes(payload);
            if ((flags & FLAG_END_STREAM) != 0) {
                responseEnded = true;
                monitor.notifyAll();
            }
        }
        if (payload.length > 0) {
            write(out, TYPE_WINDOW_UPDATE, 0, streamId, int32(payload.length));
            write(out, TYPE_WINDOW_UPDATE, 0, 0, int32(payload.length));
        }
    }

    @Override
    public void close() {
        try {
            socket.close();
        } catch (IOException _) {
            // best effort
        }
        if (reader != null) {
            reader.interrupt();
            try {
                reader.join(WAIT_MILLIS);
            } catch (InterruptedException _) {
                Thread.currentThread().interrupt();
            }
        }
    }

    // ------------------------------------------------------------------ wire

    private void write(OutputStream out, int type, int flags, int frameStreamId, byte[] payload) throws IOException {
        synchronized (writeLock) {
            writeFrame(out, type, flags, frameStreamId, payload);
            out.flush();
        }
    }

    private static void writeFrame(OutputStream out, int type, int flags, int streamId, byte[] payload)
            throws IOException {
        byte[] header = new byte[9];
        header[0] = (byte) (payload.length >>> 16);
        header[1] = (byte) (payload.length >>> 8);
        header[2] = (byte) payload.length;
        header[3] = (byte) type;
        header[4] = (byte) flags;
        header[5] = (byte) ((streamId >>> 24) & 0x7F);
        header[6] = (byte) (streamId >>> 16);
        header[7] = (byte) (streamId >>> 8);
        header[8] = (byte) streamId;
        out.write(header);
        out.write(payload);
    }

    private static byte[] int32(int value) {
        return new byte[] {(byte) (value >>> 24), (byte) (value >>> 16), (byte) (value >>> 8), (byte) value};
    }

    /** A request header block: literal without indexing, no Huffman, so it needs no server HPACK state. */
    private static byte[] requestBlock(String scheme, String path) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writeInteger(out, 0x80, 7, STATIC_METHOD_POST);
        writeInteger(out, 0x80, 7, "https".equals(scheme) ? STATIC_SCHEME_HTTPS : STATIC_SCHEME_HTTP);
        writeInteger(out, 0x00, 4, STATIC_PATH);
        writeString(out, path);
        writeInteger(out, 0x00, 4, STATIC_AUTHORITY);
        writeString(out, "localhost");
        return out.toByteArray();
    }

    private static void writeString(ByteArrayOutputStream out, String text) {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        writeInteger(out, 0x00, 7, bytes.length);
        out.writeBytes(bytes);
    }

    private static void writeInteger(ByteArrayOutputStream out, int highBits, int prefixBits, int value) {
        int maxPrefix = (1 << prefixBits) - 1;
        if (value < maxPrefix) {
            out.write(highBits | value);
            return;
        }
        out.write(highBits | maxPrefix);
        int remaining = value - maxPrefix;
        while (remaining >= 0x80) {
            out.write((remaining & 0x7F) | 0x80);
            remaining >>>= 7;
        }
        out.write(remaining);
    }
}
