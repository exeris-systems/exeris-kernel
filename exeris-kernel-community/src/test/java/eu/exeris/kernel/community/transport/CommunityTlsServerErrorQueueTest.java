/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.transport;

import eu.exeris.kernel.community.crypto.CommunityKernelCryptoProvider;
import eu.exeris.kernel.community.memory.CommunityMemoryProvider;
import eu.exeris.kernel.spi.context.KernelProviders;
import eu.exeris.kernel.spi.memory.LoanedBuffer;
import eu.exeris.kernel.spi.memory.MemoryAllocator;
import eu.exeris.kernel.spi.memory.MemoryProviderConfig;
import eu.exeris.kernel.spi.transport.TransportConfig;
import eu.exeris.kernel.spi.transport.TransportEngine;
import eu.exeris.kernel.spi.transport.TransportMode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManagerFactory;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * One client's failed handshake does not take down a healthy connection served by the same
 * reactor.
 *
 * <p>The listener has one reactor, so every server-side {@code SSL_accept} and {@code SSL_read}
 * runs on one OS thread and shares its OpenSSL error queue. A peer that speaks plaintext to the TLS
 * port fails its handshake there; if that failure left its entry on the queue, the next
 * {@code SSL_read} on a healthy connection that would block reads the entry as
 * {@code SSL_ERROR_SSL} and the healthy connection is torn down.
 *
 * <p>Discriminates on OpenSSL 3.x, whose {@code SSL_get_error} consults the queue before the
 * return code; a 4.x library does not cascade this way, and there the Core
 * {@code OffHeapTlsEngineErrorQueueIT} is the test that holds the clear.
 */
@Timeout(60)
class CommunityTlsServerErrorQueueTest {

    @TempDir
    /* default */ static Path tlsMaterialDir;

    private static final MemoryAllocator ALLOCATOR =
            new CommunityMemoryProvider().createAllocator(MemoryProviderConfig.defaults());

    private static final int ECHO_BUFFER = 256;

    @Test
    @DisplayName("a plaintext peer's failed handshake leaves a healthy TLS connection on the same reactor serving")
    void failedHandshakeDoesNotCascadeToANeighbour() throws Exception {
        TlsTestCertificate certificate = TlsTestCertificate.generateInto(tlsMaterialDir);
        CommunityKernelCryptoProvider cryptoProvider = new CommunityKernelCryptoProvider();
        int port = CommunityTransportTestHarness.nextFreePort();

        TransportEngine[] holder = new TransportEngine[1];
        ScopedValue.where(KernelProviders.MEMORY_ALLOCATOR, ALLOCATOR)
                .where(KernelProviders.CRYPTO_PROVIDER, cryptoProvider)
                .run(() -> holder[0] = new NativeTcpTransportProvider().createEngine(new TransportConfig(
                        TransportMode.SERVER, "127.0.0.1", port, 1,
                        certificate.certPath(), certificate.keyPath(), 64, 30_000)));
        TransportEngine server = holder[0];
        server.setStreamHandler(stream -> {
            try (LoanedBuffer buffer = ALLOCATOR.allocateNetwork(ECHO_BUFFER)) {
                int read = stream.read(buffer.segment(), ECHO_BUFFER);
                while (read > 0) {
                    stream.write(buffer.segment(), read);
                    read = stream.read(buffer.segment(), ECHO_BUFFER);
                }
            }
        });

        try {
            server.start();
            try (SSLSocket healthy = openTrustingClient(certificate.certificate(), port)) {
                assertThat(roundTrip(healthy, "before")).isEqualTo("before");

                refuseAPlaintextPeer(port);

                assertThat(roundTrip(healthy, "after-1"))
                        .as("the healthy connection still echoes after its neighbour's handshake failed")
                        .isEqualTo("after-1");
                assertThat(roundTrip(healthy, "after-2")).isEqualTo("after-2");
            }
        } finally {
            server.close();
        }
    }

    /**
     * Sends plaintext HTTP to the TLS port and waits until the server has dropped the connection,
     * so its failed {@code SSL_accept} has run before the healthy client speaks again.
     */
    private static void refuseAPlaintextPeer(int port) throws Exception {
        try (Socket plaintext = new Socket()) {
            plaintext.connect(new InetSocketAddress("127.0.0.1", port), 5_000);
            plaintext.setSoTimeout(10_000);
            OutputStream out = plaintext.getOutputStream();
            out.write("GET / HTTP/1.1\r\nHost: 127.0.0.1\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
            out.flush();
            InputStream in = plaintext.getInputStream();
            byte[] sink = new byte[512];
            try {
                while (in.read(sink) >= 0) {
                    // drain the alert, if any, until the server closes the socket
                }
            } catch (java.net.SocketException reset) {
                // a reset is the server dropping it too
            }
        }
    }

    /**
     * Writes {@code message} and reads its echo, reporting a broken connection as a value so the
     * assertion that follows names it.
     */
    private static String roundTrip(SSLSocket socket, String message) {
        byte[] payload = message.getBytes(StandardCharsets.US_ASCII);
        try {
            socket.getOutputStream().write(payload);
            socket.getOutputStream().flush();
            byte[] echoed = socket.getInputStream().readNBytes(payload.length);
            return new String(echoed, StandardCharsets.US_ASCII);
        } catch (java.io.IOException broken) {
            return "connection broken: " + broken;
        }
    }

    private static SSLSocket openTrustingClient(Path certificate, int port) throws Exception {
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
        SSLSocket socket = (SSLSocket) context.getSocketFactory().createSocket();
        socket.connect(new InetSocketAddress("127.0.0.1", port), 5_000);
        socket.setSoTimeout(10_000);
        socket.startHandshake();
        return socket;
    }
}
