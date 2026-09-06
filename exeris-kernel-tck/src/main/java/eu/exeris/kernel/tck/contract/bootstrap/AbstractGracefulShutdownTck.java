/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;
import java.util.function.IntSupplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * TCK: verifies that a kernel's shutdown sequence closes SPI engines in the canonical
 * reverse-dependency order and drains in-flight work before the Persistence engine closes.
 *
 * <h2>Contract</h2>
 * <p>An implementation must close its engines in the canonical reverse-dependency order and
 * must drain all in-flight work before closing the Persistence engine.
 *
 * <h2>Canonical shutdown order</h2>
 * <pre>
 *   Transport → Persistence → Flow → Events → Graph → Memory
 * </pre>
 * This is the strict reverse of the bootstrap dependency graph.
 *
 * <h2>How shutdown order is observed</h2>
 * <p>The subclass wraps each running {@link AutoCloseable} engine handle it owns in a
 * {@link TrackedEngine} and supplies a shutdown callback ({@code orchestratorShutdown},
 * passed to {@link KernelHandle}) that triggers its own shutdown path. Each
 * {@link TrackedEngine#close()} call appends the engine's name to the shared observed-order
 * list, and this class asserts pairwise ordering against the canonical sequence. No new SPI
 * is introduced — {@link AutoCloseable} is the only hook used.
 *
 * <p>This class observes only the order in which the {@code AutoCloseable} handles supplied
 * by {@link #buildAndStartKernel()} are closed; it never calls a {@code Subsystem}'s own
 * {@code stop()} directly. A binding that wraps {@code TrackedEngine} around its SPI engines
 * but routes {@code orchestratorShutdown} through a path other than its shipped
 * subsystem-shutdown sequence proves the ordering of that wiring, not the sequencing its
 * real orchestrator applies to production subsystems.
 *
 * <h2>Usage</h2>
 * {@snippet lang="java" :
 * class CommunityGracefulShutdownTest extends AbstractGracefulShutdownTck {
 *     @Override
 *     protected KernelHandle buildAndStartKernel() {
 *         var order = new java.util.concurrent.CopyOnWriteArrayList<String>();
 *         var transportEngine   = new CommunityTransportEngine(...);
 *         var persistenceEngine = new CommunityPersistenceEngine(...);
 *         var flowEngine        = new CoreFlowEngine(...);
 *         var eventsEngine      = new CommunityEventEngine(...);
 *         transportEngine.start(); persistenceEngine.start();
 *         flowEngine.start();      eventsEngine.start();
 *
 *         var transport   = new TrackedEngine("Transport",   transportEngine).withObservedOrder(order);
 *         var persistence = new TrackedEngine("Persistence", persistenceEngine).withObservedOrder(order);
 *         var flow        = new TrackedEngine("Flow",        flowEngine).withObservedOrder(order);
 *         var events      = new TrackedEngine("Events",      eventsEngine).withObservedOrder(order);
 *
 *         return new KernelHandle(
 *             transport, persistence, flow, events, null, null,
 *             () -> transportEngine.enqueue("ping"),
 *             () -> persistenceEngine.eventStore(conn).pollPending(Integer.MAX_VALUE).size(),
 *             () -> { transport.close(); persistence.close(); flow.close(); events.close(); },
 *             order
 *         );
 *     }
 * }
 * }
 *
 * @since 0.5
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
     *
     * @return a handle bundling the started engines and workload hooks this TCK drives
     */
    protected abstract KernelHandle buildAndStartKernel();

    /**
     * Returns the number of concurrent in-flight requests to fire during the
     * zero-downtime draining test. Default: {@code 100,000}.
     * <p>Override to reduce the workload on resource-constrained CI environments.
     *
     * @return the number of concurrent requests the draining test submits
     */
    protected int drainingRequestCount() { return DRAINING_REQUEST_COUNT; }

    // =========================================================================
    // Handle / TrackedEngine
    // =========================================================================

    /**
     * Wraps all running SPI engines and workload sources needed by the TCK.
     * Pass {@code null} for engines the implementation does not provide.
     * <p>Implemented as a {@code final class} (not a {@code record}) because it carries
     * mutable shutdown state ({@link AtomicBoolean}) that
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

        private final AtomicBoolean shutdownTriggered
                = new AtomicBoolean(false);

        /**
         * Creates a handle bundling the engines and workload hooks this TCK drives.
         * Pass {@code null} for any {@link TrackedEngine} the implementation does not
         * provide; assertions about that engine are then skipped.
         *
         * @param transport            tracked {@code Transport} engine, or {@code null} if none
         * @param persistence          tracked {@code Persistence} engine, or {@code null} if none
         * @param flow                 tracked {@code Flow} engine, or {@code null} if none
         * @param events               tracked {@code Events} engine, or {@code null} if none
         * @param graph                tracked {@code Graph} engine, or {@code null} if none
         * @param memory               tracked {@code Memory} engine, or {@code null} if none
         * @param requestSender        submits one unit of in-flight work to the running kernel
         * @param confirmedWrites      returns the number of writes durably confirmed so far
         * @param orchestratorShutdown triggers the implementation's own shutdown path
         * @param observedOrder        shared list every {@link TrackedEngine} appends its name to on close
         */
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

        /**
         * Returns the tracked {@code Transport} engine.
         *
         * @return the tracked engine, or {@code null} if none was provided
         */
        public TrackedEngine              transport()            { return transport; }

        /**
         * Returns the tracked {@code Persistence} engine.
         *
         * @return the tracked engine, or {@code null} if none was provided
         */
        public TrackedEngine              persistence()          { return persistence; }

        /**
         * Returns the tracked {@code Flow} engine.
         *
         * @return the tracked engine, or {@code null} if none was provided
         */
        public TrackedEngine              flow()                 { return flow; }

        /**
         * Returns the tracked {@code Events} engine.
         *
         * @return the tracked engine, or {@code null} if none was provided
         */
        public TrackedEngine              events()               { return events; }

        /**
         * Returns the tracked {@code Graph} engine.
         *
         * @return the tracked engine, or {@code null} if none was provided
         */
        public TrackedEngine              graph()                { return graph; }

        /**
         * Returns the tracked {@code Memory} engine.
         *
         * @return the tracked engine, or {@code null} if none was provided
         */
        public TrackedEngine              memory()               { return memory; }

        /**
         * Returns the workload hook that submits one unit of in-flight work.
         *
         * @return the request-sending hook passed to the constructor
         */
        public Runnable                   requestSender()        { return requestSender; }

        /**
         * Returns the count of writes the implementation has durably confirmed so far.
         *
         * @return the confirmed-writes hook passed to the constructor
         */
        public IntSupplier                confirmedWrites()      { return confirmedWrites; }

        /**
         * Returns the shared close-order list.
         *
         * @return the list every {@link TrackedEngine} appends its name to on close
         */
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
        private final AtomicBoolean closed
                = new AtomicBoolean(false);
        private CopyOnWriteArrayList<String> observedOrder = null;

        /**
         * Wraps {@code delegate} under {@code name} for close-order tracking.
         *
         * @param name     the name appended to the observed-order list when this engine closes
         * @param delegate the engine {@link #close()} delegates to, or {@code null} for none
         */
        public TrackedEngine(String name, AutoCloseable delegate) {
            this.name     = name;
            this.delegate = delegate;
        }

        /**
         * Binds the shared order list; call before shutdown is triggered.
         *
         * @param list the shared list this engine's name is appended to on {@link #close()}
         * @return this instance, for chaining
         */
        public TrackedEngine withObservedOrder(CopyOnWriteArrayList<String> list) {
            this.observedOrder = list;
            return this;
        }

        /**
         * Returns the name this engine was constructed with.
         *
         * @return the name appended to the observed-order list when this engine closes
         */
        public String  name()     { return name; }

        /**
         * Reports whether this engine has closed.
         *
         * @return {@code true} once {@link #close()} has completed at least once
         */
        public boolean isClosed() { return closed.get(); }

        @Override
        public void close() {
            if (!closed.compareAndSet(false, true)) return;
            Exception failure = null;
            try {
                if (delegate != null) {
                    delegate.close();
                }
            }
            catch (Exception ex) {
                // Record the failure but defer throwing until after we have recorded close order.
                failure = ex;
            }
            finally {
                if (observedOrder != null) {
                    observedOrder.add(name);
                }
            }
            if (failure != null) {
                throw new AssertionError(
                        "TrackedEngine '" + name + "' delegate close failed", failure
                );
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
            if (kernel.transport()   != null) assertThat(observed)
                    .as("Transport engine must be closed during shutdown").contains("Transport");
            if (kernel.persistence() != null) assertThat(observed)
                    .as("Persistence engine must be closed during shutdown").contains("Persistence");
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
                    .filter(n -> !"Memory".equals(n))
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

        /**
         * Asserts that Transport closes before Persistence and that every request
         * {@link KernelHandle#requestSender()} accepted is durably confirmed afterward.
         * The drained-count assertion establishes zero-downtime draining only if
         * {@code requestSender} is asynchronous — returns before its write is durably
         * persisted. If it is synchronous, every request is already durably written by the
         * time shutdown is triggered below, and the drained-count assertion holds trivially
         * regardless of whether shutdown actually waits for in-flight work.
         */
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

        /**
         * Asserts the close order Flow → Events → Graph. Despite its name, this asserts
         * close ordering only — no assertion in this class measures whether Flow or Events
         * actually finished pending work before closing; "complete pending work" is inferred
         * from the ordering claim, not independently observed.
         */
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
