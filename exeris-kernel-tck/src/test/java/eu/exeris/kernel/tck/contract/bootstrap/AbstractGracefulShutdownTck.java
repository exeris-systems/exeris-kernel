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
import java.util.concurrent.locks.LockSupport;
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

    /**
     * Returns the number of concurrent in-flight requests to fire during the
     * zero-downtime draining test. Default: {@code 100,000}.
     * <p>Override to reduce the workload on resource-constrained CI environments.
     */
    protected int drainingRequestCount() { return DRAINING_REQUEST_COUNT; }

    // =========================================================================
    // Handle / TrackedEngine
    // =========================================================================

    /**
     * Wraps all running SPI engines and workload sources needed by the TCK.
     * Pass {@code null} for engines the implementation does not provide.
     * <p>Implemented as a {@code final class} (not a {@code record}) because it carries
     * mutable shutdown state ({@link java.util.concurrent.atomic.AtomicBoolean}) that
     * records cannot express as non-component fields.
     */
    public static final class KernelHandle {

        private final TrackedEngine transport;
        private final TrackedEngine persistence;
        private final TrackedEngine flow;
        private final TrackedEngine events;
        private final TrackedEngine graph;
        private final TrackedEngine memory;
        private final Runnable      requestSender;
        private final IntSupplier   confirmedWrites;
        private final Runnable      orchestratorShutdown;
        private final CopyOnWriteArrayList<String> observedOrder;

        private final java.util.concurrent.atomic.AtomicBoolean shutdownTriggered
                = new java.util.concurrent.atomic.AtomicBoolean(false);

        public KernelHandle(
                TrackedEngine transport,
                TrackedEngine persistence,
                TrackedEngine flow,
                TrackedEngine events,
                TrackedEngine graph,
                TrackedEngine memory,
                Runnable      requestSender,
                IntSupplier   confirmedWrites,
                Runnable      orchestratorShutdown,
                CopyOnWriteArrayList<String> observedOrder) {
            this.transport           = transport;
            this.persistence         = persistence;
            this.flow                = flow;
            this.events              = events;
            this.graph               = graph;
            this.memory              = memory;
            this.requestSender       = requestSender;
            this.confirmedWrites     = confirmedWrites;
            this.orchestratorShutdown = orchestratorShutdown;
            this.observedOrder       = observedOrder;
        }

        public TrackedEngine              transport()            { return transport; }
        public TrackedEngine              persistence()          { return persistence; }
        public TrackedEngine              flow()                 { return flow; }
        public TrackedEngine              events()               { return events; }
        public TrackedEngine              graph()                { return graph; }
        public TrackedEngine              memory()               { return memory; }
        public Runnable                   requestSender()        { return requestSender; }
        public IntSupplier                confirmedWrites()      { return confirmedWrites; }
        public CopyOnWriteArrayList<String> observedOrder()      { return observedOrder; }

        /**
         * Triggers the real orchestrator shutdown and returns the observed close sequence.
         * Each {@link TrackedEngine} appended its name to {@link #observedOrder} when
         * its {@code close()} was invoked by the orchestrator.
         * <p>Idempotent — subsequent calls return the already-observed order without
         * triggering a second shutdown.
         */
        List<String> shutdownInCanonicalOrder() {
            if (!shutdownTriggered.compareAndSet(false, true)) {
                return List.copyOf(observedOrder);
            }
            orchestratorShutdown.run();

            // Fence: Await all engines to close to support fully asynchronous
            // orchestrator implementations before validating the recorded order.
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (!allClosed() && System.nanoTime() < deadline) {
                LockSupport.parkNanos(
                        TimeUnit.MILLISECONDS.toNanos(10));
            }

            return List.copyOf(observedOrder);
        }

        /** Returns {@code true} when every non-null {@link TrackedEngine} has been closed. */
        private boolean allClosed() {
            if (transport   != null && !transport.isClosed())   return false;
            if (persistence != null && !persistence.isClosed()) return false;
            if (flow        != null && !flow.isClosed())        return false;
            if (events      != null && !events.isClosed())      return false;
            if (graph       != null && !graph.isClosed())       return false;
            if (memory      != null && !memory.isClosed())      return false;
            return true;
        }
    }

    /**
     * Wraps an {@link AutoCloseable} SPI engine and records when {@link #close()} was called.
     * {@link #close()} is idempotent.
     */
    public static final class TrackedEngine implements AutoCloseable {

        private final String        name;
        private final AutoCloseable delegate;
        private final java.util.concurrent.atomic.AtomicBoolean closed
                = new java.util.concurrent.atomic.AtomicBoolean(false);
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
        public boolean isClosed() { return closed.get(); }

        @Override
        public void close() {
            if (!closed.compareAndSet(false, true)) return;
            try { if (delegate != null) delegate.close(); }
            catch (Exception _) { /* close must not throw in TCK context */ }
            finally {
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

            // ── Presence assertion ────────────────────────────────────────────
            // Every non-null TrackedEngine MUST appear in the observed close list.
            // assertClosedBefore() returns early when a name is absent, so without
            // this guard a silently-leaked (never-closed) engine would produce a
            // false-pass on the pairwise ordering checks below.
            if (kernel.transport()   != null) assertThat(observed).as("Transport engine must be closed during shutdown").contains("Transport");
            if (kernel.persistence() != null) assertThat(observed).as("Persistence engine must be closed during shutdown").contains("Persistence");
            if (kernel.flow()        != null) assertThat(observed).as("Flow engine must be closed during shutdown").contains("Flow");
            if (kernel.events()      != null) assertThat(observed).as("Events engine must be closed during shutdown").contains("Events");
            if (kernel.graph()       != null) assertThat(observed).as("Graph engine must be closed during shutdown").contains("Graph");
            if (kernel.memory()      != null) assertThat(observed).as("Memory engine must be closed during shutdown").contains("Memory");

            // ── Pairwise ordering checks ──────────────────────────────────────
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
            int           count  = drainingRequestCount();
            AtomicInteger sent   = new AtomicInteger();
            AtomicInteger errors = new AtomicInteger();
            CountDownLatch fired = new CountDownLatch(count);

            for (int i = 0; i < count; i++) {
                Thread.ofVirtual().name("exeris-drain-", i).start(() -> {
                    try {
                        kernel.requestSender().run();
                        sent.incrementAndGet();
                    } catch (Throwable _) {
                        errors.incrementAndGet();
                    } finally {
                        fired.countDown();
                    }
                });
            }

            assertThat(fired.await(60, TimeUnit.SECONDS))
                    .as("All %d requests must be submitted within 60 s", count)
                    .isTrue();

            assertThat(errors.get())
                    .as("Request submission errors must be zero before measuring drain count")
                    .isZero();

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
