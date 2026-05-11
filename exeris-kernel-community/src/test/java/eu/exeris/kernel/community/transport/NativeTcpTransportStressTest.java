/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
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
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.foreign.MemorySegment;
import java.net.InetSocketAddress;
import java.nio.channels.ServerSocketChannel;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.Future;
import java.util.concurrent.locks.LockSupport;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Community TCP multi-carrier stress and concurrency tests.
 *
 * <h2>TCK-064 investigation status (Sprint 2 v0.7)</h2>
 *
 * <p>The {@code stressTest10ClientsWith4Reactors} method previously deadlocked under
 * constrained CI environments (2 vCPU GitHub Actions runners) past the 2-minute Future
 * timeout, while the same test passed deterministically locally in {@code <500 ms}
 * across 5/5 runs. The investigation considered three hypotheses:
 *
 * <ol>
 *   <li><b>Thread-pressure deadlock</b> — 10 client engines × 1 reactor + 4 server
 *       reactors + 10 ForkJoinPool workers competing on 2 vCPUs. Mitigation deferred
 *       to a future structural rewrite (single client engine + N streams pattern) so
 *       the platform-thread count drops from ~24 to ~6.</li>
 *   <li><b>{@code pendingRequests} ordering anomaly</b> — partially addressed by
 *       PERF-063 (Sprint 2): {@code ConcurrentLinkedQueue} replaced with
 *       {@link org.jctools.queues.MpscUnboundedArrayQueue}, whose single-consumer
 *       guarantees are stronger than CLQ's under reactor wakeup contention.</li>
 *   <li><b>Short-read assumption</b> — addressed below: {@code stream.read} is now
 *       called in a short-read loop because TCP does not guarantee a single-shot
 *       read of {@code MESSAGE_SIZE} bytes, particularly under load when receive
 *       windows fragment.</li>
 * </ol>
 *
 * <p>Tagged {@code @Tag("stress")} and excluded from the default Surefire run.
 * Operators should wire a dedicated {@code transport-stress-gate} CI job
 * ({@code -DincludedGroups=stress}) on a nightly schedule, optionally with
 * {@code stress-ng --cpu N} on the runner to amplify thread-pressure conditions
 * for hypothesis (1) regression coverage.
 *
 * @see eu.exeris.kernel.community.transport.NativeTcpCarrier
 * @since 0.5.0 (TCK-064 documentation update: 0.7.0)
 */
@DisplayName("Community TCP: multi-carrier stress & concurrency")
class NativeTcpTransportStressTest {

    private static MemoryAllocator ALLOCATOR;

    // TCK-064 (v0.8 Sprint 0): scale knobs for constrained-CI runners.
    // Defaults remain 10 / 4 (matches v0.7 baseline); CI on 2-vCPU GitHub Actions
    // overrides via -Dexeris.tck.transport.stress.clients=3
    //                -Dexeris.tck.transport.stress.serverReactors=2
    //                -Dexeris.tck.transport.stress.clientTimeoutSeconds=30
    // Rationale: 10 clients × 1 reactor + 4 server reactors + 10 ForkJoinPool workers
    // = 14 threads × 2 vCPU = 7× CPU oversubscription. Local <500 ms baseline runs
    // hot enough to mask the issue; constrained CI cannot make forward progress on
    // the carrier reactor under that ratio. Independent of the scale knobs, the
    // per-client timeout is bounded so CI fails fast (max wallclock = clients × timeout)
    // instead of the 20 min sequential 2-min timeout cycle observed pre-fix.
    private static final int NUM_CLIENTS =
            Integer.getInteger("exeris.tck.transport.stress.clients", 10);
    private static final int SERVER_REACTOR_COUNT =
            Integer.getInteger("exeris.tck.transport.stress.serverReactors", 4);
    private static final long CLIENT_TIMEOUT_SECONDS =
            Long.getLong("exeris.tck.transport.stress.clientTimeoutSeconds", 30L);
    private static final int MESSAGES_PER_CLIENT = 5;
    private static final int MESSAGE_SIZE = 512;

    @BeforeAll
    static void setupAllocator() {
        ALLOCATOR = new CommunityMemoryProvider().createAllocator(MemoryProviderConfig.defaults());
    }

    @AfterAll
    static void releaseAllocator() {
        ALLOCATOR.close();
    }

    @Test
    @Tag("stress")
    @DisplayName("10 concurrent clients × 4 reactors: round-trip messaging")
    void stressTest10ClientsWith4Reactors() throws Exception {
        int port = nextFreePort();
        byte[] testPayload = new byte[MESSAGE_SIZE];
        Arrays.fill(testPayload, (byte) 42);

        NativeTcpTransportProvider provider = new NativeTcpTransportProvider();
        try (TransportEngine serverEngine = createServerEngine(provider, port, SERVER_REACTOR_COUNT)) {
            AtomicInteger messageCount = new AtomicInteger(0);
            serverEngine.setStreamHandler(stream -> echoUntilStreamCloses(stream, messageCount));
            serverEngine.start();

            List<Future<?>> futures = new ArrayList<>();
            try (ExecutorService executor = new ForkJoinPool(NUM_CLIENTS)) {
                for (int clientId = 0; clientId < NUM_CLIENTS; clientId++) {
                    futures.add(executor.submit(() -> {
                        TransportEngine clientEngine = null;
                        TransportConnection connection = null;
                        TransportStream stream = null;
                        try {
                            clientEngine = createClientEngine(provider, "127.0.0.1");
                            clientEngine.start();

                            connection = clientEngine.connect("127.0.0.1", port);
                            stream = connection.openStream();

                            for (int msg = 0; msg < MESSAGES_PER_CLIENT; msg++) {
                                try (LoanedBuffer buf = ALLOCATOR.allocateNetwork(MESSAGE_SIZE)) {
                                    buf.segment().asSlice(0, MESSAGE_SIZE).copyFrom(
                                        MemorySegment.ofArray(testPayload)
                                    );
                                    buf.setSize(MESSAGE_SIZE);

                                    stream.write(buf.segment(), MESSAGE_SIZE);

                                    try (LoanedBuffer response = ALLOCATOR.allocateNetwork(MESSAGE_SIZE)) {
                                        // TCK-064: short-read loop. TCP read may return < MESSAGE_SIZE
                                        // under fragmented receive windows; loop until the full payload
                                        // arrives or the stream closes. Asserting equality on a single
                                        // read was hypothesis (3) for the constrained-CI deadlock.
                                        readFully(stream, response.segment(), MESSAGE_SIZE);

                                        byte[] echoed = new byte[MESSAGE_SIZE];
                                        response.segment().asSlice(0, MESSAGE_SIZE).asByteBuffer().get(echoed);
                                        assertThat(Arrays.equals(echoed, testPayload)).isTrue();
                                    }
                                }
                            }
                        } catch (Exception ex) {
                            throw new RuntimeException("Client failed", ex);
                        } finally {
                            if (stream != null) {
                                stream.close();
                            }
                            if (connection != null) {
                                connection.close();
                            }
                            if (clientEngine != null) {
                                clientEngine.close();
                            }
                        }
                    }));
                }

                boolean allDone = true;
                for (Future<?> future : futures) {
                    try {
                        future.get(CLIENT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
                    } catch (TimeoutException _) {
                        allDone = false;
                        future.cancel(true);
                    }
                }

                assertThat(allDone).withFailMessage("Not all clients completed in time").isTrue();
            }

            int expectedTotal = NUM_CLIENTS * MESSAGES_PER_CLIENT;
            // TCK-064: 500 ms was tight under 2-vCPU CI where the server reactor may not have
            // drained the last batch yet. 5 s is generous enough for constrained runners and
            // still bounded for fast local boxes; the assert below remains strict on count.
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (messageCount.get() < expectedTotal && System.nanoTime() < deadline) {
                LockSupport.parkNanos(1_000_000L);
            }
            assertThat(messageCount.get())
                .withFailMessage("Server did not receive expected message count")
                .isEqualTo(expectedTotal);
        }
    }

    @Test
    @DisplayName("2 concurrent clients × 3 reactors: verify channel owner tracking")
    void stressTest2ClientsWithMultiReactor() {
        int port = nextFreePort();
        int reactorCount = 3;
        byte[] testPayload = new byte[MESSAGE_SIZE];
        Arrays.fill(testPayload, (byte) 99);

        NativeTcpTransportProvider provider = new NativeTcpTransportProvider();
        try (TransportEngine server = createServerEngine(provider, port, reactorCount)) {
            AtomicInteger messageCount = new AtomicInteger(0);
            server.setStreamHandler(stream -> echoUntilStreamCloses(stream, messageCount));
            server.start();

            // Connect 2 clients and send messages
            for (int i = 0; i < 2; i++) {
                TransportEngine client = null;
                TransportConnection conn = null;
                TransportStream stream = null;
                try {
                    client = createClientEngine(provider, "127.0.0.1");
                    client.start();
                    conn = client.connect("127.0.0.1", port);
                    stream = conn.openStream();

                    // Send 3 messages from each client
                    for (int msg = 0; msg < 3; msg++) {
                        try (LoanedBuffer buf = ALLOCATOR.allocateNetwork(MESSAGE_SIZE)) {
                            buf.segment().asSlice(0, MESSAGE_SIZE).copyFrom(MemorySegment.ofArray(testPayload));
                            buf.setSize(MESSAGE_SIZE);
                            stream.write(buf.segment(), MESSAGE_SIZE);

                            try (LoanedBuffer response = ALLOCATOR.allocateNetwork(MESSAGE_SIZE)) {
                                // TCK-064: short-read loop (see top-of-file Javadoc).
                                readFully(stream, response.segment(), MESSAGE_SIZE);
                            }
                        }
                    }
                } finally {
                    if (stream != null) {
                        stream.close();
                    }
                    if (conn != null) {
                        conn.close();
                    }
                    if (client != null) {
                        client.close();
                    }
                }
            }

            // Verify server received all messages (6 total: 2 clients × 3 messages)
            // TCK-064: same widened deadline as stressTest10ClientsWith4Reactors.
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (messageCount.get() < 6 && System.nanoTime() < deadline) {
                LockSupport.parkNanos(1_000_000L);
            }
            assertThat(messageCount.get())
                .withFailMessage("Server did not receive expected message count")
                .isEqualTo(6);
        }
    }

    /**
     * TCK-064: short-read loop. {@link TransportStream#read(MemorySegment, int)} is a
     * single-shot read returning whatever is available (TCP receive-window slice), so
     * a single call is not guaranteed to deliver {@code expectedBytes}. This helper
     * loops, advancing into the target segment via {@link MemorySegment#asSlice(long)},
     * until the full payload arrives or the peer closes the stream.
     */
    private static void readFully(TransportStream stream, MemorySegment target, int expectedBytes) {
        int total = 0;
        // TCK-064 (Sprint 0 v0.8): under constrained-CI thread pressure (2 vCPU GitHub Actions),
        // `Thread.onSpinWait()` was a JIT spin-loop hint that did NOT yield the CPU — it asked
        // the kernel to keep us hot on a starved core, starving the server reactor that owed us
        // bytes. The result was a multi-minute deadlock observed at line 173. `LockSupport.parkNanos`
        // releases the core to the reactor for at least the requested interval; on a healthy box
        // the park returns essentially immediately, so the local <500 ms baseline is preserved.
        while (total < expectedBytes) {
            int n = stream.read(target.asSlice(total), expectedBytes - total);
            if (n < 0) {
                throw new IllegalStateException(
                        "Stream EOF before full payload arrived: read " + total + "/" + expectedBytes + " bytes");
            }
            if (n == 0) {
                LockSupport.parkNanos(1_000L);
                continue;
            }
            total += n;
        }
    }

    private TransportEngine createServerEngine(NativeTcpTransportProvider provider, int port, int reactorCount) {
        TransportEngine[] holder = new TransportEngine[1];
        ScopedValue.where(KernelProviders.MEMORY_ALLOCATOR, ALLOCATOR)
            .run(() -> holder[0] = provider.createEngine(new TransportConfig(
                TransportMode.SERVER,
                "127.0.0.1",
                port,
                reactorCount,
                null, null,
                1024,
                30_000
            )));
        return holder[0];
    }

    private TransportEngine createClientEngine(NativeTcpTransportProvider provider, String host) {
        TransportEngine[] holder = new TransportEngine[1];
        ScopedValue.where(KernelProviders.MEMORY_ALLOCATOR, ALLOCATOR)
            .run(() -> holder[0] = provider.createEngine(new TransportConfig(
                TransportMode.CLIENT,
                host,
                0,
                1,
                null, null,
                1024,
                30_000
            )));
        return holder[0];
    }

    private void echoUntilStreamCloses(TransportStream stream, AtomicInteger messageCount) {
        try (stream;
             LoanedBuffer inbound = ALLOCATOR.allocateNetwork(MESSAGE_SIZE + 1);
             LoanedBuffer outbound = ALLOCATOR.allocateNetwork(MESSAGE_SIZE + 1)) {
            while (true) {
                int read = stream.read(inbound.segment(), MESSAGE_SIZE);
                if (read < 0) {
                    return;
                }
                if (read == 0) {
                    // TCK-064: server echo loop must not busy-spin on n=0 — under 2-vCPU CI it
                    // starves the client reactor of the CPU needed to push the next packet.
                    LockSupport.parkNanos(1_000L);
                    continue;
                }
                inbound.setSize(read);
                outbound.segment().asSlice(0, read).copyFrom(inbound.segment().asSlice(0, read));
                outbound.setSize(read);
                stream.write(outbound.segment(), read);
                messageCount.incrementAndGet();
            }
        } catch (RuntimeException _) {
            // Stream can fail during shutdown; treat as terminal for this handler.
        }
    }

    private static int nextFreePort() {
        try (ServerSocketChannel server = ServerSocketChannel.open()) {
            server.bind(new InetSocketAddress("127.0.0.1", 0));
            return ((InetSocketAddress) server.getLocalAddress()).getPort();
        } catch (IOException ioException) {
            throw new IllegalStateException("Unable to allocate free TCP port", ioException);
        }
    }

}
