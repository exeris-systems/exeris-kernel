/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.storage;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * An S3 endpoint just large enough for the driver's single-object calls, on {@code localhost}, that
 * records what reached it.
 *
 * <p>It answers {@code PUT}, {@code HEAD}, {@code GET} and {@code DELETE} on a request target from an
 * in-memory map, checks no signature, and keeps each connection alive until the client closes it. It
 * records every request's method, target and {@code Host}, and every connection it accepts. A
 * connection whose first byte opens a TLS record ({@code 0x16}) is counted and closed unread, so a
 * client that dials TLS here fails at once instead of waiting on a server that waits for a request.
 */
final class S3StubServer implements AutoCloseable {

    private static final int SOCKET_TIMEOUT_MILLIS = 10_000;
    private static final int MAX_HEAD_BYTES = 16 * 1024;
    private static final int TLS_HANDSHAKE_RECORD = 0x16;

    /**
     * One request the stub read.
     *
     * @param method the request method
     * @param target the request target
     * @param host   the {@code Host} header, or {@code null}
     */
    record Request(String method, String target, String host) {
    }

    private record StoredObject(byte[] bytes, String contentType) {
    }

    private record Head(String method, String target, Map<String, String> headers) {

        String header(String name) {
            return headers.get(name.toLowerCase(Locale.ROOT));
        }

        int contentLength() {
            String value = header("content-length");
            return value == null ? 0 : Integer.parseInt(value.strip());
        }
    }

    private final ServerSocket listener;
    private final Thread acceptor;
    private final AtomicInteger accepted = new AtomicInteger();
    private final AtomicInteger tlsRecordsRefused = new AtomicInteger();
    private final List<Request> requests = new CopyOnWriteArrayList<>();
    private final Map<String, StoredObject> objects = new ConcurrentHashMap<>();
    private final List<Socket> open = new CopyOnWriteArrayList<>();

    private S3StubServer(ServerSocket listener) {
        this.listener = listener;
        this.acceptor = Thread.ofPlatform().daemon().name("s3-stub-acceptor").unstarted(this::acceptAll);
    }

    /**
     * Starts a plaintext stub on an ephemeral port of {@code localhost}.
     *
     * @return the started stub
     * @throws IOException if the port cannot be bound
     */
    static S3StubServer plaintext() throws IOException {
        S3StubServer server = new S3StubServer(new ServerSocket(0, 16, InetAddress.getByName("localhost")));
        server.acceptor.start();
        return server;
    }

    int port() {
        return listener.getLocalPort();
    }

    /** Every connection accepted, including one refused for opening with a TLS record. */
    int acceptedConnections() {
        return accepted.get();
    }

    /** Connections closed unread because their first byte opened a TLS record. */
    int tlsRecordsRefused() {
        return tlsRecordsRefused.get();
    }

    /** The requests read, in arrival order. */
    List<Request> requests() {
        return List.copyOf(requests);
    }

    @Override
    public void close() throws IOException {
        listener.close();
        for (Socket socket : open) {
            socket.close();
        }
        try {
            acceptor.join(TimeUnit.SECONDS.toMillis(5));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void acceptAll() {
        while (!listener.isClosed()) {
            Socket socket;
            try {
                socket = listener.accept();
            } catch (IOException closed) {
                return;
            }
            accepted.incrementAndGet();
            open.add(socket);
            Thread.ofPlatform().daemon().name("s3-stub-connection").start(() -> serve(socket));
        }
    }

    private void serve(Socket socket) {
        try (socket) {
            socket.setSoTimeout(SOCKET_TIMEOUT_MILLIS);
            InputStream in = new BufferedInputStream(socket.getInputStream());
            OutputStream out = socket.getOutputStream();
            in.mark(1);
            if (in.read() == TLS_HANDSHAKE_RECORD) {
                tlsRecordsRefused.incrementAndGet();
                return;
            }
            in.reset();
            Head head;
            while ((head = readHead(in)) != null) {
                byte[] body = in.readNBytes(head.contentLength());
                requests.add(new Request(head.method(), head.target(), head.header("host")));
                respond(out, head, body);
            }
        } catch (IOException gone) {
            // The client closed the connection, or it idled past the timeout: nothing to answer.
        } finally {
            open.remove(socket);
        }
    }

    private void respond(OutputStream out, Head head, byte[] body) throws IOException {
        StoredObject stored = objects.get(head.target());
        switch (head.method()) {
            case "PUT" -> {
                objects.put(head.target(), new StoredObject(body, head.header("content-type")));
                write(out, "200 OK", Map.of("Content-Length", "0"), null);
            }
            case "HEAD", "GET" -> {
                if (stored == null) {
                    write(out, "404 Not Found", Map.of("Content-Length", "0"), null);
                } else {
                    Map<String, String> headers = new HashMap<>();
                    headers.put("Content-Length", Integer.toString(stored.bytes().length));
                    headers.put("Content-Type", stored.contentType() == null
                            ? "application/octet-stream"
                            : stored.contentType());
                    write(out, "200 OK", headers, "GET".equals(head.method()) ? stored.bytes() : null);
                }
            }
            case "DELETE" -> {
                objects.remove(head.target());
                write(out, "204 No Content", Map.of(), null);
            }
            default -> write(out, "405 Method Not Allowed", Map.of("Content-Length", "0"), null);
        }
    }

    private static void write(OutputStream out, String status, Map<String, String> headers, byte[] body)
            throws IOException {
        StringBuilder head = new StringBuilder("HTTP/1.1 ").append(status).append("\r\n");
        headers.forEach((name, value) -> head.append(name).append(": ").append(value).append("\r\n"));
        head.append("\r\n");
        out.write(head.toString().getBytes(StandardCharsets.ISO_8859_1));
        if (body != null) {
            out.write(body);
        }
        out.flush();
    }

    /** Reads one request head, or returns {@code null} if the stream ends before one starts. */
    private static Head readHead(InputStream in) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        int matched = 0;
        byte[] terminator = {'\r', '\n', '\r', '\n'};
        while (matched < terminator.length) {
            int next = in.read();
            if (next < 0) {
                if (bytes.size() == 0) {
                    return null;
                }
                throw new IOException("stream ended inside a request head");
            }
            bytes.write(next);
            if (bytes.size() > MAX_HEAD_BYTES) {
                throw new IOException("request head exceeds " + MAX_HEAD_BYTES + " bytes");
            }
            if (next == terminator[matched]) {
                matched++;
            } else {
                matched = next == '\r' ? 1 : 0;
            }
        }
        String[] lines = bytes.toString(StandardCharsets.ISO_8859_1).split("\r\n");
        String[] requestLine = lines[0].split(" ");
        Map<String, String> headers = new HashMap<>();
        for (int index = 1; index < lines.length; index++) {
            int colon = lines[index].indexOf(':');
            if (colon > 0) {
                headers.put(lines[index].substring(0, colon).strip().toLowerCase(Locale.ROOT),
                        lines[index].substring(colon + 1).strip());
            }
        }
        return new Head(requestLine[0], requestLine[1], headers);
    }
}
