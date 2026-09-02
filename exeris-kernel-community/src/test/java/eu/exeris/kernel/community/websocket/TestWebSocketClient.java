/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.websocket;

import eu.exeris.kernel.spi.http.HttpStatus;
import eu.exeris.kernel.spi.websocket.WebSocketCloseCode;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Optional;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * A deliberately independent RFC 6455 client, for the TCK binding to drive the server with.
 *
 * <p><b>It does not use the kernel's own codec, and that is the point.</b> A fixture that framed its
 * messages with {@code WebSocketFrameWriter} and read them with {@code WebSocketFrameParser} would
 * agree with the server about any mistake they shared — the classic way a codec test passes while
 * neither end speaks the protocol. Frames here are assembled and read byte by byte against the
 * specification text.
 *
 * <p>{@code java.net.Socket} and {@code java.io} are on the scoped-ban list for production runtime
 * hot paths; this is a test fixture, which the ban explicitly does not cover.
 */
final class TestWebSocketClient implements AutoCloseable {

    private static final byte[] MASK_KEY = {0x37, (byte) 0xFA, 0x21, 0x3D};
    private static final int MAX_CONTROL_PAYLOAD = 125;

    private final Socket socket;
    private final OutputStream out;
    private final DataInputStream in;
    private final Thread reader;

    private final BlockingQueue<String> inbound = new ArrayBlockingQueue<>(64);
    private final AtomicInteger observedCloseCode = new AtomicInteger();
    private final AtomicReference<HttpStatus> handshakeStatus = new AtomicReference<>();
    private final AtomicReference<String> subprotocol = new AtomicReference<>();

    private volatile boolean running = true;

    TestWebSocketClient(int port, String origin, String requestedSubprotocol) throws IOException {
        this.socket = new Socket();
        this.socket.connect(new InetSocketAddress("127.0.0.1", port), 5_000);
        this.socket.setSoTimeout(15_000);
        this.out = socket.getOutputStream();
        this.in = new DataInputStream(socket.getInputStream());
        performHandshake(port, origin, requestedSubprotocol);
        this.reader = new Thread(this::readLoop, "test-ws-client-reader");
        this.reader.setDaemon(true);
        if (accepted()) {
            this.reader.start();
        }
    }

    boolean accepted() {
        HttpStatus status = handshakeStatus.get();
        return status != null && status.code() == HttpStatus.SWITCHING_PROTOCOLS.code();
    }

    /**
     * @return the REFUSAL status, or empty when the handshake was accepted. The TCK reads it that
     *     way -- {@code isEmpty()} on the accept path, {@code contains(...)} on both refusal paths --
     *     so a 101 is reported as "nothing to refuse with", not as a status.
     */
    Optional<HttpStatus> handshakeStatus() {
        return accepted() ? Optional.empty() : Optional.ofNullable(handshakeStatus.get());
    }

    Optional<String> negotiatedSubprotocol() {
        return Optional.ofNullable(subprotocol.get());
    }

    private void performHandshake(int port, String origin, String requestedSubprotocol)
            throws IOException {
        StringBuilder request = new StringBuilder(256)
                .append("GET /ws HTTP/1.1\r\n")
                .append("Host: 127.0.0.1:").append(port).append("\r\n")
                .append("Upgrade: websocket\r\n")
                .append("Connection: keep-alive, Upgrade\r\n")
                .append("Sec-WebSocket-Version: 13\r\n")
                .append("Sec-WebSocket-Key: ")
                .append(Base64.getEncoder().encodeToString("0123456789abcdef".getBytes(
                        StandardCharsets.US_ASCII)))
                .append("\r\n");
        if (origin != null) {
            request.append("Origin: ").append(origin).append("\r\n");
        }
        if (requestedSubprotocol != null) {
            request.append("Sec-WebSocket-Protocol: ").append(requestedSubprotocol).append("\r\n");
        }
        request.append("\r\n");
        out.write(request.toString().getBytes(StandardCharsets.US_ASCII));
        out.flush();
        readHandshakeResponse();
    }

    private void readHandshakeResponse() throws IOException {
        StringBuilder response = new StringBuilder(256);
        // Byte at a time up to the terminal CRLF CRLF: reading in blocks risks swallowing the first
        // frame the server sends immediately after the 101.
        int consecutiveNewlines = 0;
        while (consecutiveNewlines < 2) {
            int read = in.read();
            if (read < 0) {
                return;
            }
            response.append((char) read);
            if (read == '\n') {
                consecutiveNewlines++;
            } else if (read != '\r') {
                consecutiveNewlines = 0;
            }
        }
        String text = response.toString();
        String[] statusParts = text.split("\r\n", 2)[0].split(" ", 3);
        handshakeStatus.set(new HttpStatus(Integer.parseInt(statusParts[1]),
                statusParts.length > 2 ? statusParts[2] : ""));
        for (String line : text.split("\r\n")) {
            if (line.toLowerCase(java.util.Locale.ROOT).startsWith("sec-websocket-protocol:")) {
                subprotocol.set(line.substring(line.indexOf(':') + 1).trim());
            }
        }
    }

    void sendText(String message) {
        writeFrame(0x1, true, message.getBytes(StandardCharsets.UTF_8));
    }

    void sendBinary(byte[] payload) {
        writeFrame(0x2, true, payload);
    }

    void sendFragmented(String message, int fragments) {
        byte[] bytes = message.getBytes(StandardCharsets.UTF_8);
        int size = (bytes.length + fragments - 1) / fragments;
        for (int i = 0; i < fragments; i++) {
            int from = i * size;
            int to = Math.min(bytes.length, from + size);
            byte[] slice = java.util.Arrays.copyOfRange(bytes, from, to);
            // First fragment carries the opcode, the rest CONTINUATION; only the last sets FIN.
            writeFrame(i == 0 ? 0x1 : 0x0, i == fragments - 1, slice);
        }
    }

    void sendClose(WebSocketCloseCode code) {
        byte[] payload = {(byte) (code.code() >>> 8), (byte) code.code()};
        writeFrame(0x8, true, payload);
    }

    private synchronized void writeFrame(int opcode, boolean fin, byte[] payload) {
        try {
            out.write((fin ? 0x80 : 0x00) | opcode);
            // Client-to-server frames MUST be masked (RFC 6455 §5.3); the mask bit is always set.
            if (payload.length <= MAX_CONTROL_PAYLOAD) {
                out.write(0x80 | payload.length);
            } else if (payload.length <= 0xFFFF) {
                out.write(0x80 | 126);
                out.write(payload.length >>> 8);
                out.write(payload.length & 0xFF);
            } else {
                out.write(0x80 | 127);
                for (int shift = 56; shift >= 0; shift -= 8) {
                    out.write((int) ((long) payload.length >>> shift) & 0xFF);
                }
            }
            out.write(MASK_KEY);
            byte[] masked = new byte[payload.length];
            for (int i = 0; i < payload.length; i++) {
                masked[i] = (byte) (payload[i] ^ MASK_KEY[i & 3]);
            }
            out.write(masked);
            out.flush();
        } catch (IOException _) {
            running = false;
        }
    }

    private void readLoop() {
        try {
            while (running) {
                int first = in.read();
                if (first < 0) {
                    return;
                }
                int opcode = first & 0x0F;
                int second = in.read();
                if (second < 0) {
                    return;
                }
                long length = second & 0x7F;
                if (length == 126) {
                    length = in.readUnsignedShort();
                } else if (length == 127) {
                    length = in.readLong();
                }
                byte[] payload = new byte[(int) length];
                in.readFully(payload);
                // Server-to-client frames must NOT be masked, so no unmasking happens here; a
                // server that masked one would produce garbage, which is the correct outcome.
                if (opcode == 0x1 || opcode == 0x0) {
                    inbound.offer(new String(payload, StandardCharsets.UTF_8));
                } else if (opcode == 0x8) {
                    observedCloseCode.set(payload.length >= 2
                            ? ((payload[0] & 0xFF) << 8) | (payload[1] & 0xFF)
                            : WebSocketCloseCode.NORMAL_CLOSURE.code());
                    return;
                }
            }
        } catch (IOException _) {
            // A closed socket ends the loop; the test asserts on what arrived before it.
            running = false;
        }
    }

    String receive(long timeout, TimeUnit unit) {
        try {
            return inbound.poll(timeout, unit);
        } catch (InterruptedException _) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    Optional<Integer> observedCloseCode(long timeout, TimeUnit unit) {
        long deadline = System.nanoTime() + unit.toNanos(timeout);
        while (System.nanoTime() < deadline) {
            int code = observedCloseCode.get();
            if (code != 0) {
                return Optional.of(code);
            }
            Thread.onSpinWait();
        }
        int code = observedCloseCode.get();
        return code == 0 ? Optional.empty() : Optional.of(code);
    }

    @Override
    public void close() {
        running = false;
        try {
            socket.close();
        } catch (IOException _) {
            // Closing an already-dead socket is the normal end of a scenario.
        }
    }
}
