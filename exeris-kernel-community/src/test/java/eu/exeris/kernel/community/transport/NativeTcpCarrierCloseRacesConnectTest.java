/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.transport;

import eu.exeris.kernel.community.crypto.CommunityKernelCryptoProvider;
import eu.exeris.kernel.community.memory.CommunityMemoryProvider;
import eu.exeris.kernel.spi.context.KernelProviders;
import eu.exeris.kernel.spi.exceptions.ExerisKernelException;
import eu.exeris.kernel.spi.memory.LeakDetectionMode;
import eu.exeris.kernel.spi.memory.MemoryAllocator;
import eu.exeris.kernel.spi.memory.MemoryProviderConfig;
import eu.exeris.kernel.spi.transport.TransportConfig;
import eu.exeris.kernel.spi.transport.TransportConnection;
import eu.exeris.kernel.spi.transport.TransportEngine;
import eu.exeris.kernel.spi.transport.TransportMode;
import eu.exeris.kernel.spi.transport.TransportStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.ServerSocket;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Smoke test: a verifying client carrier closed while connects are in flight. Closing the carrier
 * releases its trust store while another thread may be handing that store to a new context; the
 * lease on {@code CommunityTlsClientTrust} is what keeps that safe, and
 * {@code CommunityTlsClientTrustTest} is the test that pins it. This one only shows the combination
 * does not crash, leak, or fail in a way the connect contract does not name.
 */
@Timeout(value = 120, unit = TimeUnit.SECONDS)
@DisplayName("NativeTcpCarrier — close() racing verifying connects")
class NativeTcpCarrierCloseRacesConnectTest {

    private static final int ITERATIONS = 50;
    private static final int CONNECTORS = 8;

    @Test
    @DisplayName("every connect racing close() succeeds or fails with a kernel or state exception")
    void closeRacingConnectsIsSafe(@TempDir Path material) throws Exception {
        TlsTestCertificate certificate = TlsTestCertificate.generateInto(material);
        CommunityKernelCryptoProvider crypto = new CommunityKernelCryptoProvider();
        ConcurrentLinkedQueue<Throwable> unexpected = new ConcurrentLinkedQueue<>();
        AtomicInteger connected = new AtomicInteger();

        try (MemoryAllocator allocator = new CommunityMemoryProvider().createAllocator(
                MemoryProviderConfig.defaults().withLeakDetection(LeakDetectionMode.PARANOID))) {
            int port = freePort();
            TransportEngine server = build(allocator, crypto, new TransportConfig(TransportMode.SERVER, "127.0.0.1",
                    port, 1, certificate.certPath(), certificate.keyPath(), 1024, 30_000));
            server.setStreamHandler(stream -> { });
            server.start();
            try {
                for (int iteration = 0; iteration < ITERATIONS; iteration++) {
                    raceOnce(allocator, crypto, certificate, port, unexpected, connected);
                }
            } finally {
                server.close();
            }
        }

        assertThat(unexpected).as("failures outside the connect contract").isEmpty();
        assertThat(connected.get()).as("some connects won the race").isPositive();
    }

    private static void raceOnce(MemoryAllocator allocator, CommunityKernelCryptoProvider crypto,
                                 TlsTestCertificate certificate, int port,
                                 ConcurrentLinkedQueue<Throwable> unexpected, AtomicInteger connected)
            throws InterruptedException {
        TransportEngine client = ScopedValue.where(KernelProviders.MEMORY_ALLOCATOR, allocator)
                .where(KernelProviders.CRYPTO_PROVIDER, crypto)
                .where(KernelProviders.CURRENT_CONFIG, certificate.clientTrust())
                .call(() -> new NativeTcpTransportProvider().createEngine(new TransportConfig(
                        TransportMode.CLIENT, "127.0.0.1", 0, 1, null, null, 1024, 30_000)));
        client.start();
        CountDownLatch go = new CountDownLatch(1);
        CountDownLatch firstConnected = new CountDownLatch(1);
        List<Thread> connectors = new ArrayList<>();
        for (int i = 0; i < CONNECTORS; i++) {
            connectors.add(Thread.ofPlatform().start(() -> {
                awaitQuietly(go);
                try {
                    TransportConnection connection = client.connect("127.0.0.1", port);
                    connected.incrementAndGet();
                    firstConnected.countDown();
                    TransportStream stream = connection.openStream();
                    stream.close();
                } catch (ExerisKernelException | IllegalStateException expected) {
                    // a connect that lost the race to close() fails inside its contract
                } catch (Throwable other) {
                    unexpected.add(other);
                }
            }));
        }
        go.countDown();
        // Close as soon as one connect has won, so the other connectors are mid-connect when the
        // trust store is released.
        firstConnected.await(10, TimeUnit.SECONDS);
        client.close();
        for (Thread connector : connectors) {
            connector.join(TimeUnit.SECONDS.toMillis(30));
            assertThat(connector.isAlive()).as("a connector hung").isFalse();
        }
    }

    private static TransportEngine build(MemoryAllocator allocator, CommunityKernelCryptoProvider crypto,
                                         TransportConfig config) {
        return ScopedValue.where(KernelProviders.MEMORY_ALLOCATOR, allocator)
                .where(KernelProviders.CRYPTO_PROVIDER, crypto)
                .where(KernelProviders.CURRENT_CONFIG, new MapConfigProvider(Map.of(), Map.of()))
                .call(() -> new NativeTcpTransportProvider().createEngine(config));
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}
