/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.transport;

import eu.exeris.kernel.community.crypto.CommunityTlsEngineTestAccess;
import eu.exeris.kernel.community.crypto.SocketChannelFdAccess;
import eu.exeris.kernel.community.memory.CommunityMemoryProvider;
import eu.exeris.kernel.core.crypto.openssl.ScriptedOpenSsl;
import eu.exeris.kernel.spi.crypto.CryptoProviderConfig;
import eu.exeris.kernel.spi.crypto.KernelCryptoProvider;
import eu.exeris.kernel.spi.crypto.TlsEngine;
import eu.exeris.kernel.spi.memory.MemoryAllocator;
import eu.exeris.kernel.spi.memory.MemoryProviderConfig;
import eu.exeris.kernel.spi.transport.TransportConfig;
import eu.exeris.kernel.spi.transport.TransportMode;
import eu.exeris.kernel.spi.transport.TransportStats;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * An accepted connection whose listener TLS engine fails to bind to the socket releases the engine
 * and the socket, each once, and the carrier keeps accepting.
 *
 * <p>The listener's crypto provider hands the carrier a real {@code CommunityTlsEngine} over
 * {@link ScriptedOpenSsl} whose {@code SSL_set_fd} returns {@code 0}, so every bind throws
 * {@code TlsHandshakeException} before any stream exists. The engine's close is counted as the
 * script's {@code SSL_CTX_free}; the socket's close is observed by the client as end-of-stream or a
 * reset.
 */
@Timeout(value = 30, unit = TimeUnit.SECONDS)
@DisplayName("NativeTcpCarrier — an accepted socket whose TLS engine fails to bind releases both")
class NativeTcpCarrierAcceptTlsBindFailureTest {

    private static final int ATTEMPTS = 3;
    private static final int CONNECT_TIMEOUT_MS = 2_000;
    private static final int READ_TIMEOUT_MS = 5_000;
    private static final long SETTLE_TIMEOUT_SECONDS = 10L;

    private static final MemoryAllocator ALLOCATOR =
            new CommunityMemoryProvider().createAllocator(MemoryProviderConfig.defaults());

    @AfterAll
    @SuppressWarnings("unused")
    static void releaseAllocator() {
        ALLOCATOR.close();
    }

    @Test
    @DisplayName("every engine built for a failed bind is closed, and every socket is closed")
    void failedBindClosesTheEngineAndTheSocket(@TempDir Path tmp) throws Exception {
        assertThat(SocketChannelFdAccess.isRuntimeFdAccessAvailable())
                .as("without a resolvable descriptor the bind never reaches SSL_set_fd")
                .isTrue();
        ScriptedOpenSsl openSsl = new ScriptedOpenSsl().setFdResult(0);
        UnbindableListenerCrypto crypto = new UnbindableListenerCrypto(openSsl);
        int port = freePort();
        NativeTcpCarrier carrier = new NativeTcpCarrier(
                new TransportConfig(TransportMode.SERVER, "127.0.0.1", port, 1,
                        "unused.pem", "unused.key", 1024, 30_000),
                ALLOCATOR,
                crypto,
                CryptoProviderConfig.httpsServer(tmp.resolve("cert.pem"), tmp.resolve("key.pem")),
                NativeTcpClientTls.none());
        carrier.setStreamHandler(stream -> { });

        List<Socket> clients = new ArrayList<>();
        try {
            carrier.start();
            for (int i = 0; i < ATTEMPTS; i++) {
                clients.add(connect(port));
            }
            TransportStats stats = awaitSettled(carrier);

            assertThat(crypto.created).as("one listener engine per accepted socket").hasValue(ATTEMPTS);
            assertThat(openSsl.ctxFrees())
                    .as("an engine whose bind failed is owned by no stream, so the carrier must close it")
                    .isEqualTo(ATTEMPTS);
            for (Socket client : clients) {
                assertThat(peerClosed(client))
                        .as("the accepted socket is closed, so its client sees end-of-stream or a reset")
                        .isTrue();
            }
            assertThat(stats.totalRejected()).as("nothing was declined; setup failed").isZero();
        } finally {
            carrier.close();
            for (Socket client : clients) {
                client.close();
            }
        }
    }

    /**
     * Polls the carrier until every attempt is recorded as an accept fault and has released its
     * connection slot, or the bound elapses. The slot is released after the fault is counted, so
     * both are awaited.
     */
    private static TransportStats awaitSettled(NativeTcpCarrier carrier) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(SETTLE_TIMEOUT_SECONDS);
        TransportStats stats = carrier.stats();
        while ((stats.acceptFaults() < ATTEMPTS || stats.activeConnections() != 0)
                && System.nanoTime() < deadline) {
            Thread.onSpinWait();
            stats = carrier.stats();
        }
        assertThat(stats.acceptFaults()).as("every accepted socket failed its bind").isEqualTo(ATTEMPTS);
        assertThat(stats.activeConnections()).as("each failed accept releases its slot").isZero();
        return stats;
    }

    private static Socket connect(int port) throws IOException {
        Socket socket = new Socket();
        socket.connect(new InetSocketAddress("127.0.0.1", port), CONNECT_TIMEOUT_MS);
        socket.setSoTimeout(READ_TIMEOUT_MS);
        return socket;
    }

    /** {@code true} if the server closed the socket: end-of-stream or a reset, not a timeout. */
    private static boolean peerClosed(Socket client) throws IOException {
        try {
            return client.getInputStream().read() == -1;
        } catch (SocketTimeoutException _) {
            return false;
        } catch (SocketException _) {
            return true;
        }
    }

    private static int freePort() throws IOException {
        try (ServerSocket probe = new ServerSocket(0)) {
            return probe.getLocalPort();
        }
    }

    /** Builds, per accepted socket, a scripted listener engine whose bind fails. */
    private static final class UnbindableListenerCrypto implements KernelCryptoProvider {

        private final ScriptedOpenSsl openSsl;
        private final AtomicInteger created = new AtomicInteger();

        UnbindableListenerCrypto(ScriptedOpenSsl openSsl) {
            this.openSsl = openSsl;
        }

        @Override
        public TlsEngine createTlsEngine(CryptoProviderConfig config) {
            created.incrementAndGet();
            return CommunityTlsEngineTestAccess.scriptedServer(openSsl, ALLOCATOR);
        }

        @Override
        public boolean supportsQuic() {
            return false;
        }

        @Override
        public String providerName() {
            return "TestUnbindableListenerCrypto";
        }
    }
}
