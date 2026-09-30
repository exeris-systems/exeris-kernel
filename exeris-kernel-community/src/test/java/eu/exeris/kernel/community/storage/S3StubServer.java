/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.storage;

import javax.net.ssl.ExtendedSSLSession;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SNIHostName;
import javax.net.ssl.SNIServerName;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
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
 * records every request's method, target and {@code Host}, and every connection it accepts.
 *
 * <p>{@link #plaintext()} counts and closes unread a connection whose first byte opens a TLS record
 * ({@code 0x16}), so a client that dials TLS there fails at once instead of waiting on a server that
 * waits for a request. {@link #tls} serves over the JDK's own TLS stack, independent of the provider
 * under test, records the server name each completed handshake requested, and counts a handshake
 * that fails as a connection that served nothing.
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

    private static final char[] KEY_PASSWORD = "stub".toCharArray();

    private final ServerSocket listener;
    private final boolean tls;
    private final Thread acceptor;
    private final AtomicInteger accepted = new AtomicInteger();
    private final AtomicInteger tlsRecordsRefused = new AtomicInteger();
    private final AtomicInteger handshakesFailed = new AtomicInteger();
    private final List<String> serverNames = new CopyOnWriteArrayList<>();
    private final List<Request> requests = new CopyOnWriteArrayList<>();
    private final Map<String, StoredObject> objects = new ConcurrentHashMap<>();
    private final List<Socket> open = new CopyOnWriteArrayList<>();

    private S3StubServer(ServerSocket listener, boolean tls) {
        this.listener = listener;
        this.tls = tls;
        this.acceptor = Thread.ofPlatform().daemon().name("s3-stub-acceptor").unstarted(this::acceptAll);
    }

    /**
     * Starts a plaintext stub on an ephemeral port of {@code localhost}.
     *
     * @return the started stub
     * @throws IOException if the port cannot be bound
     */
    static S3StubServer plaintext() throws IOException {
        S3StubServer server = new S3StubServer(new ServerSocket(0, 16, InetAddress.getByName("localhost")), false);
        server.acceptor.start();
        return server;
    }

    /**
     * Starts a TLS stub on an ephemeral port of {@code localhost}, presenting {@code certificate}.
     *
     * @param certificate the PEM certificate to present
     * @param privateKey  its PEM PKCS#8 key
     * @return the started stub
     * @throws IOException              if the port cannot be bound or a file cannot be read
     * @throws GeneralSecurityException if the material cannot be loaded
     */
    static S3StubServer tls(Path certificate, Path privateKey) throws IOException, GeneralSecurityException {
        X509Certificate leaf;
        try (InputStream in = Files.newInputStream(certificate)) {
            leaf = (X509Certificate) CertificateFactory.getInstance("X.509").generateCertificate(in);
        }
        String pem = Files.readString(privateKey, StandardCharsets.US_ASCII);
        String body = pem.replaceAll("-----(BEGIN|END) PRIVATE KEY-----", "").replaceAll("\\s", "");
        PrivateKey key = KeyFactory.getInstance(leaf.getPublicKey().getAlgorithm())
                .generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(body)));
        KeyStore store = KeyStore.getInstance("PKCS12");
        store.load(null, null);
        store.setKeyEntry("leaf", key, KEY_PASSWORD, new Certificate[]{leaf});
        KeyManagerFactory keys = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        keys.init(store, KEY_PASSWORD);
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(keys.getKeyManagers(), null, null);
        S3StubServer server = new S3StubServer(context.getServerSocketFactory()
                .createServerSocket(0, 16, InetAddress.getByName("localhost")), true);
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

    /**
     * The server name each completed TLS handshake requested, {@code null} for none, in order. The
     * copy keeps a {@code null}, so a handshake that named no server is reported, not thrown on.
     */
    List<String> serverNames() {
        return Collections.unmodifiableList(new ArrayList<>(serverNames));
    }

    /** TLS handshakes that failed, each on a connection that served nothing. */
    int handshakesFailed() {
        return handshakesFailed.get();
    }

    @Override
    public void close() throws IOException {
        listener.close();
        for (Socket socket : open) {
            socket.close();
        }
        try {
            acceptor.join(TimeUnit.SECONDS.toMillis(5));
        } catch (InterruptedException _) {
            Thread.currentThread().interrupt();
        }
    }

    private void acceptAll() {
        while (!listener.isClosed()) {
            Socket socket;
            try {
                socket = listener.accept();
            } catch (IOException _) {
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
            if (tls && !handshake((SSLSocket) socket)) {
                return;
            }
            InputStream in = new BufferedInputStream(socket.getInputStream());
            OutputStream out = socket.getOutputStream();
            if (!tls && opensWithTlsRecord(in)) {
                tlsRecordsRefused.incrementAndGet();
                return;
            }
            Head head;
            while ((head = readHead(in)) != null) {
                byte[] body = in.readNBytes(head.contentLength());
                requests.add(new Request(head.method(), head.target(), head.header("host")));
                respond(out, head, body);
            }
        } catch (IOException _) {
            // The client closed the connection, or it idled past the timeout: nothing to answer.
        } finally {
            open.remove(socket);
        }
    }

    /** Completes the server side of the handshake and records the requested server name. */
    private boolean handshake(SSLSocket socket) {
        try {
            socket.startHandshake();
        } catch (IOException _) {
            handshakesFailed.incrementAndGet();
            return false;
        }
        List<SNIServerName> requested = ((ExtendedSSLSession) socket.getSession()).getRequestedServerNames();
        serverNames.add(requested.isEmpty() ? null : ((SNIHostName) requested.getFirst()).getAsciiName());
        return true;
    }

    private static boolean opensWithTlsRecord(InputStream in) throws IOException {
        in.mark(1);
        boolean record = in.read() == TLS_HANDSHAKE_RECORD;
        in.reset();
        return record;
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
