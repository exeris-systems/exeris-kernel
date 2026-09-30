/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.tck.contract.http;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLSocket;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Duration;
import java.util.Base64;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The default {@link TlsPeerServer}: the JDK's own TLS stack, so the server side of every case is
 * independent of the provider under test.
 *
 * <p>One accepting thread serves connections one at a time. A connection whose handshake fails is
 * counted as finished and never as a request.
 */
final class JsseTlsPeerServer implements TlsPeerServer {

    private static final char[] KEY_PASSWORD = "tck".toCharArray();
    private static final int SOCKET_TIMEOUT_MILLIS = 10_000;
    private static final int MAX_HEAD_BYTES = 16 * 1024;
    private static final byte[] RESPONSE =
            "HTTP/1.1 200 OK\r\nContent-Length: 2\r\nConnection: close\r\n\r\nok".getBytes(StandardCharsets.US_ASCII);

    private final SSLServerSocket listener;
    private final Thread acceptor;
    private final AtomicInteger accepted = new AtomicInteger();
    private final AtomicInteger finished = new AtomicInteger();
    private final AtomicInteger requests = new AtomicInteger();

    private JsseTlsPeerServer(SSLServerSocket listener) {
        this.listener = listener;
        this.acceptor = Thread.ofPlatform().daemon().name("tck-tls-peer-server").unstarted(this::acceptAll);
    }

    /**
     * Starts a server presenting {@code leaf} on an ephemeral port of {@code bindAddress}.
     *
     * @param leaf        the certificate and key to present
     * @param bindAddress the address to accept on
     * @return the started server
     */
    static JsseTlsPeerServer start(TlsPeerFixtures.Leaf leaf, InetAddress bindAddress) {
        try {
            X509Certificate certificate = readCertificate(leaf);
            PrivateKey key = readPrivateKey(leaf, certificate.getPublicKey().getAlgorithm());
            KeyStore store = KeyStore.getInstance("PKCS12");
            store.load(null, null);
            store.setKeyEntry("leaf", key, KEY_PASSWORD, new Certificate[]{certificate});
            KeyManagerFactory keys = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
            keys.init(store, KEY_PASSWORD);
            SSLContext context = SSLContext.getInstance("TLS");
            context.init(keys.getKeyManagers(), null, null);
            SSLServerSocket listener =
                    (SSLServerSocket) context.getServerSocketFactory().createServerSocket(0, 16, bindAddress);
            JsseTlsPeerServer server = new JsseTlsPeerServer(listener);
            server.acceptor.start();
            return server;
        } catch (IOException e) {
            throw new UncheckedIOException("the TLS peer server could not start", e);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("the TLS peer server could not load " + leaf.certificate(), e);
        }
    }

    @Override
    public int port() {
        return listener.getLocalPort();
    }

    @Override
    public int requestsServed() {
        return requests.get();
    }

    @Override
    public void awaitQuiet(Duration limit) throws InterruptedException {
        long deadline = System.nanoTime() + limit.toNanos();
        while (System.nanoTime() < deadline) {
            int seen = accepted.get();
            if (seen > 0 && finished.get() == seen) {
                return;
            }
            TimeUnit.MILLISECONDS.sleep(5);
        }
    }

    @Override
    public void close() {
        try {
            listener.close();
        } catch (IOException _) {
            // Closing is best effort: the accepting thread ends on the resulting SocketException.
        }
        try {
            acceptor.join(TimeUnit.SECONDS.toMillis(SOCKET_TIMEOUT_MILLIS / 1000L + 5));
        } catch (InterruptedException _) {
            Thread.currentThread().interrupt();
        }
    }

    private void acceptAll() {
        while (!listener.isClosed()) {
            SSLSocket socket;
            try {
                socket = (SSLSocket) listener.accept();
            } catch (IOException _) {
                return;
            }
            accepted.incrementAndGet();
            try (socket) {
                serve(socket);
            } catch (IOException _) {
                // A refused handshake, or a peer that went away: not a request.
            } finally {
                finished.incrementAndGet();
            }
        }
    }

    private void serve(SSLSocket socket) throws IOException {
        socket.setSoTimeout(SOCKET_TIMEOUT_MILLIS);
        socket.startHandshake();
        if (readRequestHead(socket.getInputStream())) {
            requests.incrementAndGet();
            OutputStream out = socket.getOutputStream();
            out.write(RESPONSE);
            out.flush();
        }
    }

    /** Reads up to the blank line ending a request head; {@code false} if the stream ends first. */
    private static boolean readRequestHead(InputStream in) throws IOException {
        int matched = 0;
        byte[] terminator = {'\r', '\n', '\r', '\n'};
        for (int read = 0; read < MAX_HEAD_BYTES; read++) {
            int next = in.read();
            if (next < 0) {
                return false;
            }
            if (next == terminator[matched]) {
                matched++;
            } else {
                matched = next == '\r' ? 1 : 0;
            }
            if (matched == terminator.length) {
                return true;
            }
        }
        return false;
    }

    private static X509Certificate readCertificate(TlsPeerFixtures.Leaf leaf)
            throws IOException, GeneralSecurityException {
        try (InputStream in = Files.newInputStream(leaf.certificate())) {
            return (X509Certificate) CertificateFactory.getInstance("X.509").generateCertificate(in);
        }
    }

    private static PrivateKey readPrivateKey(TlsPeerFixtures.Leaf leaf, String algorithm)
            throws IOException, GeneralSecurityException {
        String pem = Files.readString(leaf.privateKey(), StandardCharsets.US_ASCII);
        String body = pem.replaceAll("-----(BEGIN|END) PRIVATE KEY-----", "").replaceAll("\\s", "");
        return KeyFactory.getInstance(algorithm).generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(body)));
    }
}
