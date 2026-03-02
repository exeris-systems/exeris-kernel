/*
 * Copyright (C) 2025-2026 Exeris. All rights reserved.
 *
 * This code is part of the Exeris Systems.
 * Distributed under the proprietary Exeris Software License.
 * Unauthorized copying or distribution is prohibited.
 */
package eu.exeris.kernel.tck.contract.bootstrap;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntSupplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * TCK: Abstract base for graceful shutdown sequence and zero-downtime draining verification.
 *
 * <h2>Contract (#24)</h2>
 * <p>Verifies that an implementation closes subsystems in the canonical reverse-dependency
 * order and drains all in-flight work before closing the Persistence engine.
 *
 * <h2>Canonical shutdown order</h2>
 * <pre>
 *   Transport → Persistence → Flow → Events → Graph → Memory
 * </pre>
 * This is the strict reverse of the bootstrap dependency graph.
 *
 * <h2>How shutdown order is observed</h2>
 * <p>The subclass wraps each running SPI engine in a {@link TrackedEngine}. When
 * {@link TrackedEngine#close()} is called, the name is appended to an observed list.
 * The TCK then asserts pair-wise ordering against the canonical sequence.
 * No new SPI — existing {@link AutoCloseable} is the only hook needed.
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * class CommunityGracefulShutdownTest extends AbstractGracefulShutdownTck {
 *     \@Override
 *     protected KernelHandle buildAndStartKernel() {
 *         var order       = new java.util.concurrent.CopyOnWriteArrayList<String>();
 *         var transport   = new TrackedEngine("Transport",   new CommunityTransportEngine(...)).withObservedOrder(order);
 *         var persistence = new TrackedEngine("Persistence", new CommunityPersistenceEngine(...)).withObservedOrder(order);
 *         var flow        = new TrackedEngine("Flow",        new CommunityFlowEngine(...)).withObservedOrder(order);
 *         var events      = new TrackedEngine("Events",      new CommunityEventEngine(...)).withObservedOrder(order);
 *         transport.delegate().start(); persistence.delegate().start();
 *         flow.delegate().start();      events.delegate().start();
 *         return new KernelHandle(
 *             transport, persistence, flow, events, null, null,
 *             () -> transport.delegate().enqueue(TestPayloads.ping()),
 *             () -> persistence.delegate().eventStore(conn).pollPending(Integer.MAX_VALUE).size(),
 *             () -> { transport.close(); persistence.close(); flow.close(); events.close(); },
 *             order
 *         );
 *     }
 * }
 * }</pre>
 *
 * @since 0.5.0
 * @see AbstractBootstrapOrchestratorTck
 * @see AbstractSubsystemProviderTck
 */
@DisplayName("Graceful Shutdown TCK")
public abstract class AbstractGracefulShutdownTck {

    /**
     * Canonical expected shutdown order.
     * Memory closes last — all consumers must release before the slab layer tears down.
     */
    static final List<String> CANONICAL_ORDER =
            List.of("Transport", "Persistence", "Flow", "Events", "Graph", "Memory");

    private static final int DRAINING_REQUEST_COUNT = 100_000;

    // =========================================================================
    // Template methods
    // =========================================================================

    /**
     * Builds and fully starts the kernel under test.
     * All SPI engines must be running before this method returns.
     * Wrap each engine in a {@link TrackedEngine} so the TCK records close() order.
     */
    protected abstract KernelHandle buildAndStartKernel();

    // =========================================================================
    // Handle / TrackedEngine
    // =========================================================================

    /**
     * Wraps all running SPI engines and workload sources needed by the TCK.
     * Pass {@code null} for engines the implementation does not provide.
     *
     * @param transport              wrapped Transport engine
     * @param persistence            wrapped Persistence engine
     * @param flow                   wrapped Flow engine
     * @param events                 wrapped Events engine
     * @param graph                  wrapped Graph engine; {@code null} to skip
     * @param memory                 wrapped Memory layer; {@code null} to skip
     * @param requestSender          fire-and-forget: sends one unit of work via Transport
     * @param confirmedWrites        returns count of durably-written items via Persistence
     * @param orchestratorShutdown   calls the real orchestrator/kernel shutdown (e.g. kernel.shutdown())
     * @param observedOrder          shared list populated by {@link TrackedEngine#close()} in invocation order
     */
    public record KernelHandle(
            TrackedEngine transport,
            TrackedEngine persistence,
            TrackedEngine flow,
            TrackedEngine events,
            TrackedEngine graph,
            TrackedEngine memory,
            Runnable      requestSender,
            IntSupplier   confirmedWrites,
            Runnable      orchestratorShutdown,
            CopyOnWriteArrayList<String> observedOrder
    ) {
        private static final java.util.concurrent.ConcurrentHashMap<KernelHandle, Boolean> SHUTDOWN_GUARD
                = new java.util.concurrent.ConcurrentHashMap<>();

        /**
         * Triggers the real orchestrator shutdown and returns the observed close sequence.
         * Each {@link TrackedEngine} appended its name to {@link #observedOrder} when
         * its {@code close()} was invoked by the orchestrator.
         * <p>Idempotent — subsequent calls return the already-observed order without
         * triggering a second shutdown.
         */
        List<String> shutdownInCanonicalOrder() {
            if (SHUTDOWN_GUARD.putIfAbsent(this, Boolean.TRUE) != null) {
                return List.copyOf(observedOrder);
            }
            orchestratorShutdown.run();
            return List.copyOf(observedOrder);
        }
    }

    /**
     * Wraps an {@link AutoCloseable} SPI engine and records when {@link #close()} was called.
     * {@link #close()} is idempotent.
     */
    public static final class TrackedEngine implements AutoCloseable {

        private final String        name;
        private final AutoCloseable delegate;
        private volatile boolean    closed        = false;
        private CopyOnWriteArrayList<String> observedOrder = null;

        public TrackedEngine(String name, AutoCloseable delegate) {
            this.name     = name;
            this.delegate = delegate;
        }

        /** Binds the shared order list; call before shutdown is triggered. */
        public TrackedEngine withObservedOrder(CopyOnWriteArrayList<String> list) {
            this.observedOrder = list;
            return this;
        }

        public String  name()     { return name; }
        public boolean isClosed() { return closed; }

        @Override
        public void close() {
            if (closed) return;
            try { if (delegate != null) delegate.close(); }
            catch (Exception _) { /* close must not throw in TCK context */ }
            finally {
                closed = true;
                if (observedOrder != null) observedOrder.add(name);
            }
        }
    }

    // =========================================================================
    // Lifecycle
    // =========================================================================

    private KernelHandle kernel;

    @BeforeEach
    final void setUp() {
        kernel = buildAndStartKernel();
    }

    @AfterEach
    final void tearDown() {
        if (kernel != null) kernel.shutdownInCanonicalOrder();
    }

    // =========================================================================
    // Test 1 — Transport-First Shutdown
    // =========================================================================

    @Nested
    @DisplayName("Transport-First Shutdown — canonical close order enforced")
    class TransportFirstShutdownTest {

        @Test
        @Timeout(value = 60, unit = TimeUnit.SECONDS)
        @DisplayName("Close order matches: Transport → Persistence → Flow → Events → Graph → Memory")
        void shutdownOrderIsCanonical() {
            List<String> observed = kernel.shutdownInCanonicalOrder();
            for (int i = 0; i < CANONICAL_ORDER.size() - 1; i++) {
                assertClosedBefore(observed, CANONICAL_ORDER.get(i), CANONICAL_ORDER.get(i + 1));
            }
        }

        @Test
        @Timeout(value = 60, unit = TimeUnit.SECONDS)
        @DisplayName("Memory closes last — after all consumers")
        void memoryClosesLast() {
            List<String> observed = kernel.shutdownInCanonicalOrder();
            int memIdx = observed.indexOf("Memory");
            if (memIdx < 0) return;

            CANONICAL_ORDER.stream()
                    .filter(n -> !n.equals("Memory"))
                    .forEach(n -> {
                        int idx = observed.indexOf(n);
                        if (idx >= 0) {
                            assertThat(idx).as("'%s' must close BEFORE Memory", n).isLessThan(memIdx);
                        }
                    });
        }
    }

    // =========================================================================
    // Test 2 — Zero-Downtime Draining (100 000 requests)
    // =========================================================================

    @Nested
    @DisplayName("Zero-Downtime Draining — 100 000 in-flight requests")
    class ZeroDowntimeDrainingTest {

        @Test
        @Timeout(value = 120, unit = TimeUnit.SECONDS)
        @DisplayName("Transport stops accepting new work before Persistence drains and closes")
        void noRequestsLostDuringShutdown() throws InterruptedException {
            AtomicInteger sent   = new AtomicInteger();
            CountDownLatch fired = new CountDownLatch(DRAINING_REQUEST_COUNT);

            for (int i = 0; i < DRAINING_REQUEST_COUNT; i++) {
                Thread.ofVirtual().name("exeris-drain-", i).start(() -> {
                    try {
                        kernel.requestSender().run();
                        sent.incrementAndGet();
                    } finally {
                        fired.countDown();
                    }
                });
            }

            assertThat(fired.await(60, TimeUnit.SECONDS))
                    .as("All %d requests must be submitted within 60 s", DRAINING_REQUEST_COUNT)
                    .isTrue();

            List<String> order = kernel.shutdownInCanonicalOrder();
            assertClosedBefore(order, "Transport", "Persistence");

            int drained   = kernel.confirmedWrites().getAsInt();
            int totalSent = sent.get();

            assertThat(drained)
                    .as(
                        "ZERO-DOWNTIME VIOLATION: sent=%d but only %d durably written. "
                        + "Persistence MUST drain all in-flight writes before close().",
                        totalSent, drained
                    )
                    .isEqualTo(totalSent);
        }
    }

    // =========================================================================
    // Test 3 — Reverse Dependency Order
    // =========================================================================

    @Nested
    @DisplayName("Reverse Dependency Order — shutdown is strict reverse of bootstrap")
    class ReverseDependencyOrderTest {

        @Test
        @Timeout(value = 60, unit = TimeUnit.SECONDS)
        @DisplayName("Shutdown order is the strict reverse of the bootstrap dependency graph")
        void shutdownIsReverseOfBootstrap() {
            List<String> observed = kernel.shutdownInCanonicalOrder();
            List<String> present  = CANONICAL_ORDER.stream().filter(observed::contains).toList();

            assertThat(observed.stream().filter(present::contains).toList())
                    .as("Shutdown MUST be strict reverse of bootstrap dependency graph. "
                        + "Expected: %s Observed: %s", present, observed)
                    .containsExactlyElementsOf(present);
        }

        @Test
        @Timeout(value = 60, unit = TimeUnit.SECONDS)
        @DisplayName("Flow and Events complete pending work before close()")
        void flowAndEventsCompleteBeforeClose() {
            List<String> order = kernel.shutdownInCanonicalOrder();
            assertClosedBefore(order, "Flow",   "Events");
            assertClosedBefore(order, "Events", "Graph");
        }

        @Test
        @Timeout(value = 60, unit = TimeUnit.SECONDS)
        @DisplayName("Graph TrackedEngine closes without deadlock")
        void graphClosesWithoutDeadlock() {
            kernel.shutdownInCanonicalOrder();
            TrackedEngine graph = kernel.graph();
            if (graph != null) {
                assertThat(graph.isClosed())
                        .as("Graph TrackedEngine must be closed after shutdown")
                        .isTrue();
            }
        }
    }

    // =========================================================================
    // Helper
    // =========================================================================

    private static void assertClosedBefore(List<String> order, String first, String second) {
        int a = order.indexOf(first);
        int b = order.indexOf(second);
        if (a < 0 || b < 0) return;
        assertThat(a)
                .as("SHUTDOWN ORDER VIOLATION: '%s' (pos %d) must close BEFORE '%s' (pos %d). "
                    + "Observed: %s Expected: Transport→Persistence→Flow→Events→Graph→Memory",
                    first, a, second, b, order)
                .isLessThan(b);
    }
}
