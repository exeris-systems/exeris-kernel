/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.transport;

import eu.exeris.kernel.community.crypto.CommunityKernelCryptoProvider;
import eu.exeris.kernel.community.memory.CommunityMemoryProvider;
import eu.exeris.kernel.spi.context.KernelProviders;
import eu.exeris.kernel.spi.crypto.KernelCryptoProvider;
import eu.exeris.kernel.spi.memory.LoanedBuffer;
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
import java.lang.reflect.Field;
import java.net.StandardProtocolFamily;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The established callback must fire exactly once per TLS connection, across many ingress reads.
 *
 * <h2>Why this is its own class, and why on TLS</h2>
 * <p>{@code NativeTcpStream.fireEstablishedOnce()} is CAS-guarded, and until this test nothing
 * exercised that guard. The reason is a path asymmetry that is easy to miss: on <b>plaintext</b>
 * the method is reached from {@code markRegistrationReady()}, once per connection, so the CAS is
 * unreachable a second time and deleting it changes nothing observable. On <b>TLS</b> it is reached
 * from {@code readTlsIngressFromFd()}, which the reactor calls per drained record — so the guard is
 * the only thing standing between one connection and N {@code onConnectionEstablished} callbacks.
 *
 * <p>{@code NativeTcpCarrierIngressIntegrationTest} asserts "exactly once" but runs plaintext, so
 * its assertion cannot fail; removing the CAS leaves that whole class green. This one reddens.
 *
 * <p>Writes are spaced rather than burst, so the records arrive as separate readable events and the
 * reactor genuinely re-enters the TLS ingress path, instead of draining one event's backlog in a
 * single loop.
 */
@Timeout(60)
class NativeTcpTlsEstablishedOnceIntegrationTest {

    @TempDir
    /* default */ static Path tlsMaterialDir;

    private static final MemoryAllocator ALLOCATOR =
            new CommunityMemoryProvider().createAllocator(MemoryProviderConfig.defaults());

    private static final int INGRESS_EVENTS = 5;
    private static final long WRITE_SPACING_MILLIS = 60L;

    @Test
    @DisplayName("onConnectionEstablished fires once across many TLS ingress reads")
    void establishedFiresOnceAcrossManyTlsIngressReads() throws Exception {
        assumeTrue(isSocketFdAccessible(),
                "SocketChannel FileDescriptor access needs --add-opens java.base/sun.nio.ch "
                        + "and java.base/java.io");

        TlsTestCertificate certificate = TlsTestCertificate.generateInto(tlsMaterialDir);

        KernelCryptoProvider cryptoProvider;
        try {
            cryptoProvider = new CommunityKernelCryptoProvider();
        } catch (RuntimeException ex) {
            assumeTrue(false, "OpenSSL runtime unavailable");
            return;
        }

        byte[] unit = "established-once".getBytes(StandardCharsets.UTF_8);
        int total = unit.length * INGRESS_EVENTS;
        int port = CommunityTransportTestHarness.nextFreePort();
        AtomicInteger establishedCount = new AtomicInteger(0);
        CountDownLatch allRead = new CountDownLatch(1);

        NativeTcpTransportProvider provider = new NativeTcpTransportProvider();
        TransportEngine[] serverHolder = new TransportEngine[1];
        TransportEngine[] clientHolder = new TransportEngine[1];

        ScopedValue.where(KernelProviders.MEMORY_ALLOCATOR, ALLOCATOR)
                .where(KernelProviders.CRYPTO_PROVIDER, cryptoProvider)
                .run(() -> {
                    serverHolder[0] = provider.createEngine(new TransportConfig(
                            TransportMode.SERVER, "127.0.0.1", port, 1,
                            certificate.certPath(), certificate.keyPath(), 1024, 30_000));
                    clientHolder[0] = provider.createEngine(new TransportConfig(
                            TransportMode.CLIENT, "127.0.0.1", 0, 1, null, null, 1024, 30_000));
                });

        TransportEngine server = serverHolder[0];
        TransportEngine client = clientHolder[0];

        server.setConnectionHandler(connection -> establishedCount.incrementAndGet());
        server.setStreamHandler(stream -> {
            try (LoanedBuffer inbound = ALLOCATOR.allocateNetwork(total)) {
                int got = 0;
                while (got < total) {
                    int read = stream.read(inbound.segment().asSlice(got, total - got), total - got);
                    if (read < 0) {
                        break;
                    }
                    got += read;
                }
            } finally {
                allRead.countDown();
            }
        });

        try {
            server.start();
            client.start();

            TransportConnection connection = client.connect("127.0.0.1", port);
            boolean exchangeCompleted;
            RuntimeException exchangeFailure = null;
            try (TransportStream stream = connection.openStream();
                 LoanedBuffer outbound = ALLOCATOR.allocateNetwork(unit.length)) {
                try {
                    for (int i = 0; i < INGRESS_EVENTS; i++) {
                        outbound.segment().asSlice(0, unit.length).asByteBuffer().put(unit);
                        outbound.setSize(unit.length);
                        stream.write(outbound.segment(), unit.length);
                        // Spaced so each record is its own readable event on the server reactor,
                        // which is what re-enters readTlsIngressFromFd and therefore
                        // fireEstablishedOnce.
                        Thread.sleep(WRITE_SPACING_MILLIS);
                    }
                } catch (RuntimeException broken) {
                    // Recorded, not rethrown, so the count assertion below is the one that reports.
                    // A repeated establish tears the connection down before the loop finishes, and
                    // a raw "Stream is closed" here would say nothing about the invariant that
                    // actually broke.
                    exchangeFailure = broken;
                }
                exchangeCompleted = allRead.await(20, TimeUnit.SECONDS);
            }

            assertThat(establishedCount.get())
                    .as("one connection must produce exactly one onConnectionEstablished, however "
                            + "many times the reactor re-enters the TLS ingress path")
                    .isEqualTo(1);
            assertThat(exchangeFailure)
                    .as("the spaced exchange must complete without the connection breaking")
                    .isNull();
            assertThat(exchangeCompleted)
                    .as("the server must have read the whole spaced sequence")
                    .isTrue();
        } finally {
            client.close();
            server.close();
        }
    }

    private static boolean isSocketFdAccessible() {
        try (SocketChannel channel = SocketChannel.open(StandardProtocolFamily.INET)) {
            Field field = channel.getClass().getDeclaredField("fd");
            field.setAccessible(true);
            return field.get(channel) != null;
        } catch (IOException | ReflectiveOperationException | RuntimeException probeFailure) {
            return false;
        }
    }
}
