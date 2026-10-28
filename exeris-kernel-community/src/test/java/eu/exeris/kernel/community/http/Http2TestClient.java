/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.http;

import eu.exeris.kernel.community.memory.CommunityMemoryProvider;
import eu.exeris.kernel.core.http.hpack.HpackDecoder;
import eu.exeris.kernel.core.http.hpack.HpackDynamicTable;
import eu.exeris.kernel.core.http.http2.Http2Settings;
import eu.exeris.kernel.spi.http.StreamEvent;
import eu.exeris.kernel.spi.memory.MemoryAllocator;
import eu.exeris.kernel.spi.memory.MemoryProviderConfig;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManagerFactory;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.foreign.MemorySegment;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;

/**
 * A minimal HTTP/2 client for the stream tests, on a plain or TLS socket: it opens one request stream,
 * acknowledges the server's SETTINGS, decodes the response head, parses the Server-Sent Events carried
 * in DATA, and returns flow-control credit as it reads — unless told to stall, which withholds the
 * credit while it goes on reading, so the server's send window runs dry without the socket backing up.
 *
 * <p>The reader runs on a platform thread: blocking {@code java.net.Socket} reads can pin a carrier,
 * and the test JVM forces only two virtual-thread carriers. Writes are serialised by one lock because
 * the reader answers SETTINGS and returns credit while the test thread may be writing.
 */
final class Http2TestClient implements AutoCloseable {

    /** How the client reaches HTTP/2 on its connection. */
    enum Start {
        /** The connection preface is the first thing written, on stream 1. */
        PRIOR_KNOWLEDGE,
        /**
         * An HTTP/1.1 request with {@code Upgrade: h2c} comes first; the preface follows the {@code 101},
         * as RFC 9113 section 3.4 requires, and the request is stream 3.
         */
        H2C_UPGRADE
    }

    private static final long OBSERVE_TIMEOUT_MILLIS = 25_000L;
    private static final long CONNECT_TIMEOUT_MILLIS = 5_000L;
    private static final long PARK_SLICE_MILLIS = 5L;
    private static final int GRANT_THRESHOLD_BYTES = 1_024;
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
    private static final int STATIC_METHOD_GET = 2;
    private static final int STATIC_PATH = 4;
    private static final int STATIC_SCHEME_HTTP = 6;
    private static final int STATIC_SCHEME_HTTPS = 7;
    private static final int STATIC_AUTHORITY = 1;
    private static final byte[] PREFACE =
            "PRI * HTTP/2.0\r\n\r\nSM\r\n\r\n".getBytes(StandardCharsets.US_ASCII);
    private static final MemoryAllocator ALLOCATOR =
            new CommunityMemoryProvider().createAllocator(MemoryProviderConfig.defaults());

    private final Socket socket;
    private final int requestStreamId;
    private final Object writeLock = new Object();
    private final Object eventLock = new Object();
    private final List<StreamEvent> events = new ArrayList<>();
    private final AtomicBoolean stalled = new AtomicBoolean();
    private final AtomicBoolean stopped = new AtomicBoolean();
    private final HpackDecoder decoder = new HpackDecoder(new HpackDynamicTable(4096), ALLOCATOR, 65_536, 65_536);
    private final StringBuilder pending = new StringBuilder();
    private volatile boolean endOfStream;
    private volatile int resetCode = -1;
    private volatile boolean goAway;
    private volatile List<Map.Entry<String, String>> head;
    private String upgradeHead;
    private final AtomicLong ungranted = new AtomicLong();
    private int cursor;
    private Thread reader;

    private Http2TestClient(Socket socket, int requestStreamId) {
        this.socket = socket;
        this.requestStreamId = requestStreamId;
    }

    /**
     * Connects in cleartext and requests {@code path} on the first stream.
     *
     * @param port          the server's port on the loopback
     * @param path          the request path
     * @param initialWindow the {@code SETTINGS_INITIAL_WINDOW_SIZE} the client advertises
     * @param start         how HTTP/2 is reached
     * @return a client that is already reading
     */
    static Http2TestClient connectPlain(int port, String path, int initialWindow, Start start) {
        try {
            Socket socket = new Socket();
            socket.connect(new InetSocketAddress("127.0.0.1", port), (int) CONNECT_TIMEOUT_MILLIS);
            socket.setTcpNoDelay(true);
            return begin(socket, path, initialWindow, start, "http");
        } catch (IOException ex) {
            throw new IllegalStateException("Unable to connect the HTTP/2 test client", ex);
        }
    }

    /**
     * Connects over TLS with ALPN {@code h2}, requires the server to select it, and requests
     * {@code path} on the first stream.
     *
     * @param port          the server's port on the loopback
     * @param trust         the context that trusts the server's certificate
     * @param path          the request path
     * @param initialWindow the {@code SETTINGS_INITIAL_WINDOW_SIZE} the client advertises
     * @return a client that is already reading
     */
    static Http2TestClient connectTls(int port, SSLContext trust, String path, int initialWindow) {
        try {
            SSLSocket socket = (SSLSocket) trust.getSocketFactory().createSocket();
            socket.connect(new InetSocketAddress("127.0.0.1", port), (int) CONNECT_TIMEOUT_MILLIS);
            SSLParameters parameters = socket.getSSLParameters();
            parameters.setApplicationProtocols(new String[] {"h2"});
            // The certificate names IP:127.0.0.1, so the client verifies the server it dialled, not only the chain.
            parameters.setEndpointIdentificationAlgorithm("HTTPS");
            socket.setSSLParameters(parameters);
            socket.startHandshake();
            if (!"h2".equals(socket.getApplicationProtocol())) {
                socket.close();
                throw new IllegalStateException("the server did not select h2 through ALPN: "
                        + socket.getApplicationProtocol());
            }
            return begin(socket, path, initialWindow, Start.PRIOR_KNOWLEDGE, "https");
        } catch (IOException ex) {
            throw new IllegalStateException("Unable to connect the HTTP/2 TLS test client", ex);
        }
    }

    /**
     * Builds a context that trusts exactly the certificate at {@code certificate}.
     *
     * @param certificate the PEM certificate of the server
     * @return a TLS 1.3 context
     * @throws Exception when the certificate cannot be read
     */
    static SSLContext trusting(Path certificate) throws Exception {
        X509Certificate server;
        try (InputStream in = Files.newInputStream(certificate)) {
            server = (X509Certificate) CertificateFactory.getInstance("X.509").generateCertificate(in);
        }
        KeyStore trust = KeyStore.getInstance(KeyStore.getDefaultType());
        trust.load(null, null);
        trust.setCertificateEntry("server", server);
        TrustManagerFactory factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        factory.init(trust);
        SSLContext context = SSLContext.getInstance("TLSv1.3");
        context.init(null, factory.getTrustManagers(), null);
        return context;
    }

    private static Http2TestClient begin(Socket socket, String path, int initialWindow, Start start,
                                         String scheme) throws IOException {
        int streamId = start == Start.H2C_UPGRADE ? 3 : 1;
        Http2TestClient client = new Http2TestClient(socket, streamId);
        OutputStream out = socket.getOutputStream();
        synchronized (client.writeLock) {
            if (start == Start.H2C_UPGRADE) {
                out.write(("GET /upgrade HTTP/1.1\r\nHost: localhost\r\nConnection: keep-alive, Upgrade\r\n"
                        + "Upgrade: h2c\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
                out.flush();
                socket.setSoTimeout((int) OBSERVE_TIMEOUT_MILLIS);
                client.upgradeHead = readUpgradeHead(socket.getInputStream());
                socket.setSoTimeout(0);
            }
            out.write(PREFACE);
            writeFrame(out, TYPE_SETTINGS, 0, 0, settingsPayload(initialWindow));
            writeFrame(out, TYPE_HEADERS, FLAG_END_STREAM | FLAG_END_HEADERS, streamId,
                    requestBlock(scheme, path));
            out.flush();
        }
        client.reader = Thread.ofPlatform().daemon(true).name("h2-test-client")
                .start(client::readLoop);
        return client;
    }

    @SuppressWarnings("PMD.AvoidCatchingGenericException")
    private void readLoop() {
        try {
            DataInputStream in = new DataInputStream(socket.getInputStream());
            OutputStream out = socket.getOutputStream();
            while (!stopped.get()) {
                int lengthAndType = in.readInt();
                int length = lengthAndType >>> 8;
                int type = lengthAndType & 0xFF;
                int flags = in.readUnsignedByte();
                int streamId = in.readInt() & 0x7FFF_FFFF;
                byte[] payload = in.readNBytes(length);
                if (payload.length != length) {
                    return;
                }
                handle(out, type, flags, streamId, payload);
            }
        } catch (IOException | RuntimeException _) {
            // Disconnect or read interrupted: end of stream is only set by a clean END_STREAM.
        }
    }

    private static String readUpgradeHead(InputStream in) throws IOException {
        StringBuilder text = new StringBuilder();
        while (!text.toString().endsWith("\r\n\r\n")) {
            int next = in.read();
            if (next < 0) {
                throw new IOException("the connection closed before the upgrade response ended");
            }
            text.append((char) next);
        }
        return text.toString();
    }

    private void handle(OutputStream out, int type, int flags, int streamId, byte[] payload) throws IOException {
        if (type == TYPE_SETTINGS && (flags & FLAG_ACK) == 0) {
            write(out, TYPE_SETTINGS, FLAG_ACK, 0, new byte[0]);
        } else if (type == TYPE_PING && (flags & FLAG_ACK) == 0) {
            write(out, TYPE_PING, FLAG_ACK, 0, payload);
        } else if (type == TYPE_GOAWAY) {
            goAway = true;
        } else if (type == TYPE_RST_STREAM && streamId == requestStreamId) {
            resetCode = ((payload[0] & 0xFF) << 24) | ((payload[1] & 0xFF) << 16)
                    | ((payload[2] & 0xFF) << 8) | (payload[3] & 0xFF);
        } else if (type == TYPE_HEADERS && streamId == requestStreamId) {
            List<Map.Entry<String, String>> fields = new ArrayList<>();
            decoder.decode(MemorySegment.ofArray(payload), 0, payload.length,
                    (name, value, sensitive) -> fields.add(Map.entry(name, value)));
            head = List.copyOf(fields);
        } else if (type == TYPE_DATA && streamId == requestStreamId) {
            onData(out, flags, payload);
        }
    }

    private void onData(OutputStream out, int flags, byte[] payload) throws IOException {
        pending.append(new String(payload, StandardCharsets.UTF_8));
        cursor = drainEvents();
        if (cursor > 0) {
            pending.delete(0, cursor);
            cursor = 0;
        }
        if (ungranted.addAndGet(payload.length) >= GRANT_THRESHOLD_BYTES && !stalled.get()) {
            grant(out);
        }
        if ((flags & FLAG_END_STREAM) != 0) {
            endOfStream = true;
        }
    }

    private void grant(OutputStream out) throws IOException {
        long amount = ungranted.getAndSet(0L);
        if (amount > 0) {
            write(out, TYPE_WINDOW_UPDATE, 0, requestStreamId, int32((int) amount));
            write(out, TYPE_WINDOW_UPDATE, 0, 0, int32((int) amount));
        }
    }

    private int drainEvents() {
        int position = cursor;
        int idx;
        while ((idx = indexOfBlankLine(position)) >= 0) {
            String block = pending.substring(position, idx);
            position = idx + 1;
            StreamEvent parsed = parseBlock(block);
            if (parsed != null) {
                synchronized (eventLock) {
                    events.add(parsed);
                    eventLock.notifyAll();
                }
            }
        }
        return position;
    }

    private int indexOfBlankLine(int from) {
        for (int i = from; i + 1 < pending.length(); i++) {
            if (pending.charAt(i) == '\n' && pending.charAt(i + 1) == '\n') {
                return i + 1;
            }
        }
        return -1;
    }

    private static StreamEvent parseBlock(String block) {
        String event = null;
        StringBuilder data = new StringBuilder();
        boolean hasData = false;
        for (String line : block.split("\n", -1)) {
            if (line.startsWith("event:")) {
                event = line.substring("event:".length()).trim();
            } else if (line.startsWith("data:")) {
                if (hasData) {
                    data.append('\n');
                }
                data.append(line.substring("data:".length()).trim());
                hasData = true;
            }
        }
        return hasData ? new StreamEvent(event, data.toString(), null, 0L) : null;
    }

    // ------------------------------------------------------------------ observation

    /**
     * Waits until at least {@code count} events have been parsed.
     *
     * @param count how many events to wait for
     * @return the events parsed so far, in wire order
     */
    List<StreamEvent> awaitEvents(int count) {
        long deadline = System.currentTimeMillis() + OBSERVE_TIMEOUT_MILLIS;
        synchronized (eventLock) {
            while (events.size() < count && System.currentTimeMillis() < deadline) {
                long remaining = deadline - System.currentTimeMillis();
                try {
                    eventLock.wait(Math.max(1L, remaining));
                } catch (InterruptedException _) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            return List.copyOf(events);
        }
    }

    /**
     * Returns how many events have been parsed so far, without waiting.
     *
     * @return the count
     */
    int eventsParsed() {
        synchronized (eventLock) {
            return events.size();
        }
    }

    /**
     * Waits for END_STREAM on the request stream.
     *
     * @return {@code true} when the stream ended cleanly
     */
    boolean awaitEndOfStream() {
        long deadline = System.currentTimeMillis() + OBSERVE_TIMEOUT_MILLIS;
        while (!endOfStream && System.currentTimeMillis() < deadline) {
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(PARK_SLICE_MILLIS));
        }
        return endOfStream;
    }

    /**
     * Waits for the response head and returns its decoded fields.
     *
     * @return the head's fields in wire order, or {@code null} when none arrived in time
     */
    List<Map.Entry<String, String>> awaitHead() {
        long deadline = System.currentTimeMillis() + OBSERVE_TIMEOUT_MILLIS;
        while (head == null && System.currentTimeMillis() < deadline) {
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(PARK_SLICE_MILLIS));
        }
        return head;
    }

    /**
     * Returns the HTTP/1.1 head of the upgrade response.
     *
     * @return the head, or {@code null} when the client did not upgrade or none arrived
     */
    String upgradeHead() {
        return upgradeHead;
    }

    /**
     * Returns the error code of a RST_STREAM received for the request stream.
     *
     * @return the code, or {@code -1} when the stream was not reset
     */
    int resetCode() {
        return resetCode;
    }

    /**
     * Reports whether the server sent GOAWAY.
     *
     * @return {@code true} once a GOAWAY has been read
     */
    boolean goAwayReceived() {
        return goAway;
    }

    /** Withholds flow-control credit while continuing to read. */
    void stall() {
        stalled.set(true);
    }

    /** Sends the credit withheld so far and resumes returning it as DATA arrives. */
    void drain() {
        stalled.set(false);
        try {
            grant(socket.getOutputStream());
        } catch (IOException _) {
            // The connection is gone; the observation that follows reports it.
        }
    }

    /** Drops the connection. */
    void disconnect() {
        try {
            socket.close();
        } catch (IOException _) {
            // best effort
        }
    }

    @Override
    public void close() {
        stopped.set(true);
        disconnect();
        if (reader != null) {
            reader.interrupt();
            try {
                reader.join(OBSERVE_TIMEOUT_MILLIS);
            } catch (InterruptedException _) {
                Thread.currentThread().interrupt();
            }
        }
    }

    // ------------------------------------------------------------------ wire

    private void write(OutputStream out, int type, int flags, int streamId, byte[] payload) throws IOException {
        synchronized (writeLock) {
            writeFrame(out, type, flags, streamId, payload);
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

    private static byte[] settingsPayload(int initialWindow) {
        byte[] payload = new byte[6];
        payload[0] = (byte) (Http2Settings.ID_INITIAL_WINDOW_SIZE >>> 8);
        payload[1] = (byte) Http2Settings.ID_INITIAL_WINDOW_SIZE;
        System.arraycopy(int32(initialWindow), 0, payload, 2, 4);
        return payload;
    }

    private static byte[] int32(int value) {
        return new byte[] {(byte) (value >>> 24), (byte) (value >>> 16), (byte) (value >>> 8), (byte) value};
    }

    /** A request header block: literal without indexing, no Huffman, so it needs no server HPACK state. */
    private static byte[] requestBlock(String scheme, String path) {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        writeInteger(out, 0x80, 7, STATIC_METHOD_GET);
        writeInteger(out, 0x80, 7, "https".equals(scheme) ? STATIC_SCHEME_HTTPS : STATIC_SCHEME_HTTP);
        writeInteger(out, 0x00, 4, STATIC_PATH);
        writeString(out, path);
        writeInteger(out, 0x00, 4, STATIC_AUTHORITY);
        writeString(out, "localhost");
        return out.toByteArray();
    }

    private static void writeString(java.io.ByteArrayOutputStream out, String text) {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        writeInteger(out, 0x00, 7, bytes.length);
        out.writeBytes(bytes);
    }

    private static void writeInteger(java.io.ByteArrayOutputStream out, int highBits, int prefixBits, int value) {
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
