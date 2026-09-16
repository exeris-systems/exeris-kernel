/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.transport;

import eu.exeris.kernel.community.memory.CommunityMemoryProvider;
import eu.exeris.kernel.spi.context.KernelProviders;
import eu.exeris.kernel.spi.memory.LoanedBuffer;
import eu.exeris.kernel.spi.memory.MemoryAllocator;
import eu.exeris.kernel.spi.memory.MemoryProviderConfig;
import eu.exeris.kernel.spi.transport.TransportConfig;
import eu.exeris.kernel.spi.transport.TransportConnection;
import eu.exeris.kernel.spi.transport.TransportEngine;
import eu.exeris.kernel.spi.transport.TransportMode;
import eu.exeris.kernel.spi.transport.TransportStream;
import jdk.jfr.Recording;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordingFile;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.IOException;
import java.lang.foreign.MemorySegment;
import java.net.InetSocketAddress;
import java.nio.channels.ServerSocketChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * TCK-064 regression: faithful carrier-pinning verifier for the <em>client RECV</em> hot path.
 *
 * <h2>Why this test exists (the coverage gap it closes)</h2>
 * <p>{@link CommunityTransportCarrierPinningTckTest} (the {@link
 * eu.exeris.kernel.tck.contract.transport.TransportCarrierPinningTck} binding) only exercises the
 * <em>write</em> path — {@code queueWrite} on a pre-opened stream. It proves egress never pins a
 * carrier, but it never makes a client virtual thread actually <em>receive</em> bytes. TCK-064's
 * root cause was the opposite path: each client stream ran a per-stream VT that issued a blocking
 * native {@code recv()} through FFM, which <strong>pinned the carrier</strong> for the duration of
 * the blocking syscall. Under a scarce carrier pool (2-vCPU CI), N&gt;carriers concurrent clients
 * exhausted the pool and the runtime deadlocked. That defect was only ever observable through
 * {@link NativeTcpTransportStressTest} (10 clients × N reactors echo), which is {@code @Tag("stress")}
 * and excluded from the default run — so the default TCK matrix had a blind spot precisely where the
 * bug lived.
 *
 * <h2>What this test asserts</h2>
 * <p>It stands up a real loopback echo server (single server reactor) and a single client engine,
 * then fans {@value #CLIENT_COUNT} virtual threads — each one does a full
 * {@code connect → openStream → write → read(echo) → assert} round-trip through the changed
 * {@link NativeTcpCarrier} client path (non-blocking FD + client-side reactor). {@code CLIENT_COUNT}
 * is deliberately far larger than the carrier pool so the scarce-carrier deadlock would re-manifest
 * if any client RECV ever blocked a carrier again. The test fails if:
 * <ol>
 *   <li>any {@code jdk.VirtualThreadPinned} event &gt; {@value #PIN_THRESHOLD_MS} ms is recorded
 *       (the same fence the {@link eu.exeris.kernel.tck.contract.JfrPinningMonitor} uses), <em>or</em></li>
 *   <li>the client VTs do not all complete the round-trip within {@value #COMPLETION_TIMEOUT_SECONDS} s
 *       — i.e. the carrier-starvation deadlock returned.</li>
 * </ol>
 *
 * <h2>Faithful 2-vCPU carrier model</h2>
 * <p>The defect only manifests when carriers are <em>scarce</em>. A 12-core host hides it: there are
 * always free carriers even with a few pinned. This class is wired in {@code exeris-kernel-community}
 * via a dedicated Surefire execution that forks with
 * {@code -Djdk.virtualThreadScheduler.parallelism=2 -Djdk.virtualThreadScheduler.maxPoolSize=2},
 * the faithful 2-vCPU carrier model. {@code taskset} is unreliable here because the server-side
 * {@code PaqsScheduler.close()} 60 s teardown timeout dominates wall-clock under hard core-pinning;
 * constraining only the VT scheduler reproduces the carrier scarcity without that confound. When run
 * without those flags (e.g. ad-hoc {@code -Dtest=}), the assertions still hold — they are simply less
 * adversarial.
 *
 * @see CommunityTransportCarrierPinningTckTest
 * @see NativeTcpTransportStressTest
 * @since 0.8.0 (TCK-064 client-ingress regression coverage)
 */
@Tag("transport-pinning-recv")
@DisplayName("TCK-064: client RECV round-trip never pins a carrier (scarce-carrier model)")
class CommunityClientIngressCarrierPinningTest {

    private static final String VT_PINNED_EVENT = "jdk.VirtualThreadPinned";
    private static final long PIN_THRESHOLD_MS = 20L;

    /** Far larger than the 2-carrier model so the scarce-carrier deadlock would re-surface. */
    private static final int CLIENT_COUNT = 32;
    private static final int MESSAGES_PER_CLIENT = 4;
    private static final int MESSAGE_SIZE = 256;
    private static final long COMPLETION_TIMEOUT_SECONDS = 60L;

    @Test
    @Timeout(value = 90, unit = TimeUnit.SECONDS)
    @DisplayName("N>carriers clients each receive a full echo with zero carrier pinning")
    void clientRecvRoundTripDoesNotPinCarrier() throws Exception {
        MemoryAllocator allocator = new CommunityMemoryProvider().createAllocator(MemoryProviderConfig.defaults());
        int port = nextFreePort();
        byte[] payload = new byte[MESSAGE_SIZE];
        Arrays.fill(payload, (byte) 0x5A);

        NativeTcpTransportProvider provider = new NativeTcpTransportProvider();
        TransportEngine server = createEngine(provider, allocator, TransportMode.SERVER, port);
        AtomicInteger serverEchoCount = new AtomicInteger();
        server.setStreamHandler(stream -> echoUntilClosed(stream, allocator, serverEchoCount));

        // One client engine, one client reactor — N client streams multiplex onto it. This is the
        // exact production shape the TCK-064 fix targets (no per-stream blocking-recv VT).
        TransportEngine client = createEngine(provider, allocator, TransportMode.CLIENT, 0);

        Path jfr = Files.createTempFile("tck064-client-recv-pinning-", ".jfr");
        CountDownLatch done = new CountDownLatch(CLIENT_COUNT);
        AtomicInteger failures = new AtomicInteger();
        AtomicInteger completed = new AtomicInteger();

        try (Recording rec = new Recording()) {
            rec.enable(VT_PINNED_EVENT)
                    .withThreshold(Duration.ofMillis(PIN_THRESHOLD_MS))
                    .withStackTrace();
            rec.setDestination(jfr);

            server.start();
            client.start();
            rec.start();

            for (int i = 0; i < CLIENT_COUNT; i++) {
                Thread.ofVirtual()
                        .name("tck064-client-recv-", i)
                        .start(() -> {
                            try {
                                runClientRoundTrips(client, "127.0.0.1", port, allocator, payload);
                                completed.incrementAndGet();
                            } catch (RuntimeException ex) {
                                failures.incrementAndGet();
                            } finally {
                                done.countDown();
                            }
                        });
            }

            boolean allDone = done.await(COMPLETION_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            rec.stop();

            assertThat(allDone)
                    .withFailMessage(
                            "TCK-064 REGRESSION: %d/%d client RECV round-trips did not complete within %d s — "
                                    + "the scarce-carrier deadlock has returned (a client recv path is blocking a carrier).",
                            completed.get(), CLIENT_COUNT, COMPLETION_TIMEOUT_SECONDS)
                    .isTrue();
            assertThat(failures.get())
                    .withFailMessage("%d client round-trips threw during echo verification", failures.get())
                    .isZero();
            assertThat(completed.get()).isEqualTo(CLIENT_COUNT);
        } finally {
            client.close();
            server.close();
            allocator.close();
        }

        List<String> pins = readPinningEvents(jfr);
        Files.deleteIfExists(jfr);
        assertThat(pins)
                .withFailMessage(
                        "TCK-064 REGRESSION: %d carrier-pinning event(s) > %d ms during client RECV:%n%s",
                        pins.size(), PIN_THRESHOLD_MS, String.join(System.lineSeparator(), pins))
                .isEmpty();
    }

    private static void runClientRoundTrips(TransportEngine client,
                                            String host,
                                            int port,
                                            MemoryAllocator allocator,
                                            byte[] payload) {
        TransportConnection connection = client.connect(host, port);
        try (TransportStream stream = connection.openStream()) {
            for (int msg = 0; msg < MESSAGES_PER_CLIENT; msg++) {
                try (LoanedBuffer outbound = allocator.allocateNetwork(MESSAGE_SIZE);
                     LoanedBuffer inbound = allocator.allocateNetwork(MESSAGE_SIZE)) {
                    outbound.segment().asSlice(0, MESSAGE_SIZE).copyFrom(MemorySegment.ofArray(payload));
                    outbound.setSize(MESSAGE_SIZE);
                    stream.write(outbound.segment(), MESSAGE_SIZE);

                    readFully(stream, inbound.segment(), MESSAGE_SIZE);
                    byte[] echoed = new byte[MESSAGE_SIZE];
                    inbound.segment().asSlice(0, MESSAGE_SIZE).asByteBuffer().get(echoed);
                    if (!Arrays.equals(echoed, payload)) {
                        throw new IllegalStateException("echo payload mismatch");
                    }
                }
            }
        } finally {
            connection.close();
        }
    }

    private static void echoUntilClosed(TransportStream stream, MemoryAllocator allocator, AtomicInteger echoCount) {
        try (stream;
             LoanedBuffer inbound = allocator.allocateNetwork(MESSAGE_SIZE + 1);
             LoanedBuffer outbound = allocator.allocateNetwork(MESSAGE_SIZE + 1)) {
            while (true) {
                int read = stream.read(inbound.segment(), MESSAGE_SIZE);
                if (read < 0) {
                    return;
                }
                if (read == 0) {
                    LockSupport.parkNanos(1_000L);
                    continue;
                }
                inbound.setSize(read);
                outbound.segment().asSlice(0, read).copyFrom(inbound.segment().asSlice(0, read));
                outbound.setSize(read);
                stream.write(outbound.segment(), read);
                echoCount.incrementAndGet();
            }
        } catch (RuntimeException _) {
            // Stream can fail during shutdown; terminal for this handler.
        }
    }

    private static void readFully(TransportStream stream, MemorySegment target, int expectedBytes) {
        int total = 0;
        while (total < expectedBytes) {
            int n = stream.read(target.asSlice(total), expectedBytes - total);
            if (n < 0) {
                throw new IllegalStateException(
                        "Stream EOF before full payload arrived: read " + total + "/" + expectedBytes);
            }
            if (n == 0) {
                LockSupport.parkNanos(1_000L);
                continue;
            }
            total += n;
        }
    }

    private static TransportEngine createEngine(NativeTcpTransportProvider provider,
                                                MemoryAllocator allocator,
                                                TransportMode mode,
                                                int port) {
        TransportEngine[] holder = new TransportEngine[1];
        ScopedValue.where(KernelProviders.MEMORY_ALLOCATOR, allocator)
                .run(() -> holder[0] = provider.createEngine(new TransportConfig(
                        mode, "127.0.0.1", port, 1, null, null, 1024, 30_000)));
        return holder[0];
    }

    private static List<String> readPinningEvents(Path jfr) throws IOException {
        List<String> pins = new ArrayList<>();
        if (!Files.exists(jfr)) {
            return pins;
        }
        try (RecordingFile rf = new RecordingFile(jfr)) {
            while (rf.hasMoreEvents()) {
                RecordedEvent ev = rf.readEvent();
                if (!VT_PINNED_EVENT.equals(ev.getEventType().getName())) {
                    continue;
                }
                double ms = ev.getDuration().toNanos() / 1_000_000.0;
                if (ms < PIN_THRESHOLD_MS) {
                    continue;
                }
                String thread = ev.getThread() != null ? ev.getThread().getJavaName() : "<unknown>";
                pins.add(String.format(java.util.Locale.ROOT, "  - %s pinned %.2f ms", thread, ms));
            }
        }
        return pins;
    }

    private static int nextFreePort() {
        try (ServerSocketChannel server = ServerSocketChannel.open()) {
            server.bind(new InetSocketAddress("127.0.0.1", 0));
            return ((InetSocketAddress) server.getLocalAddress()).getPort();
        } catch (IOException e) {
            throw new IllegalStateException("Unable to allocate free TCP port", e);
        }
    }
}
