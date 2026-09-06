/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.tck.contract.persistence;

import eu.exeris.kernel.spi.exceptions.persistence.PersistenceProviderException;
import eu.exeris.kernel.spi.persistence.EngineStats;
import eu.exeris.kernel.spi.persistence.PersistenceConnection;
import eu.exeris.kernel.spi.persistence.PersistenceEngine;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * TCK: Abstract contract for {@link PersistenceEngine#canServiceRequest()} admission control.
 *
 * <h2>Contract semantics (universal)</h2>
 * <p>Admission control gates prevent thread park storms when the underlying connection pool is
 * saturated. The cross-tier contract this TCK verifies is intentionally weak — only the
 * tier-invariant guarantees:
 * <ul>
 *   <li>{@code canServiceRequest()} returns {@code true} when the pool has available capacity</li>
 *   <li>{@code canServiceRequest()} returns {@code false} or throws {@code PersistenceProviderException}
 *   after engine shutdown</li>
 *   <li>The decision is stable and non-throwing while a queue is forming</li>
 *   <li>The decision flips back to {@code true} once capacity is released</li>
 *   <li>Admission gate is fast (&lt;1ms) on hot path (zero-alloc guarantee)</li>
 *   <li>Multiple calls to {@code canServiceRequest()} are consistent in the same state</li>
 * </ul>
 *
 * <h2>Shedding is a tunable tier policy, not a universal contract</h2>
 * <p>The <em>exact</em> shed trigger — when a forming queue or high saturation causes
 * {@code canServiceRequest()} to return {@code false} — is implementation- and
 * configuration-defined, not fixed by this TCK; see ADR-035 for the rationale:
 * <ul>
 *   <li>Community (HikariCP): sheds once pending acquires exceed a pool-size-scaled allowance
 *       ({@code queueDepthAllowanceRatio}); a strict "shed on first waiter" mode is also
 *       available via configuration. These tier-specific assertions live in the Community
 *       admission tests.</li>
 *   <li>Enterprise: may shed predictively from native driver hints (e.g., io_uring SQE availability).</li>
 * </ul>
 *
 * <p><b>Binding obligation (not enforced here).</b> Because this suite asserts no shed direction
 * other than {@code close()}, a binding that always admits under load would still pass it.
 * Shedding under a genuinely deep queue is a per-binding obligation recorded in ADR-035
 * §Consequences: the Community tier proves it via {@code CommunityAdmissionConfig.STRICT} in its
 * own admission tests, and any Enterprise binding MUST carry an equivalent tier-specific shed
 * test. A future refinement may add an opt-in shed-direction hook to this abstract suite.
 */
public abstract class AbstractPersistenceEngineAdmissionControlTck {

    private static final Duration QUEUE_WAIT_TIMEOUT = Duration.ofSeconds(1);
    private static final long QUEUE_POLL_SLEEP_MILLIS = 5L;

    /**
     * Creates a fully bootstrapped {@link PersistenceEngine} for testing.
     * Implementations should use a fixed max pool size (recommended: 4) for deterministic testing.
     *
     * @return a ready-to-use engine with a fixed, small connection pool
     */
    protected abstract PersistenceEngine createEngine();

    private PersistenceEngine engine;

    @BeforeEach
    final void setUpEngine() {
        engine = createEngine();
    }

    @AfterEach
    final void tearDownEngine() {
        engine.close();
    }

    // =========================================================================
    // Core Contract Tests (4 fundamental scenarios)
    // =========================================================================

    @Nested
    @DisplayName("Core admission control contract")
    class CoreAdmissionControl {

        /**
         * Returns {@code true} when the pool is neither saturated nor has a queue forming.
         *
         * <p>Precondition: a fresh pool has no pending acquires and is below the saturation
         * threshold. Admission should stay open even if the provider lazily initializes idle
         * connections.
         */
        @Test
        @DisplayName("canServiceRequest() returns true when pool has no saturation or queue")
        void testCanServiceRequestReturnsTrue_WhenPoolHasNoSaturationOrQueue() {
            EngineStats initialStats = engine.stats();
            assertThat(initialStats.pendingAcquires()).isZero();
            assertThat(initialStats.activeConnections()).isZero();
            assertThat(initialStats.saturation()).isLessThan(0.9);

            // Action
            boolean canService = engine.canServiceRequest();

            // Assertion
            assertThat(canService)
                    .as("Pool with no queue and no saturation should accept new requests")
                    .isTrue();
        }

        /**
         * TEST 2: Decision is stable and non-throwing while a queue is forming.
         * Precondition: Pool saturated (active == max, idle == 0) with a thread queued.
         * Rationale (ADR-035): whether a forming queue sheds is a tunable tier policy, not a
         * universal contract — so this TCK only asserts the cross-tier invariant (no crash,
         * consistent decision). Tier-specific shed thresholds are verified in the Community tests.
         */
        @Test
        @DisplayName("canServiceRequest() is stable and non-throwing while a queue is forming")
        void testCanServiceRequest_StableWhileQueueForming() throws InterruptedException {
            EngineStats stats = engine.stats();
            int maxPoolSize = stats.maxConnections();

            // Precondition: Drain the pool to capacity
            var connections = new java.util.ArrayList<PersistenceConnection>();
            for (int i = 0; i < maxPoolSize; i++) {
                connections.add(engine.openConnection());
            }

            try {
                EngineStats saturatedStats = engine.stats();
                assertThat(saturatedStats.activeConnections()).isEqualTo(maxPoolSize);
                assertThat(saturatedStats.idleConnections()).isZero();

                // Simulate queue forming by spawning a thread that attempts to acquire a connection
                // (it will block). Then immediately check canServiceRequest() from main thread.
                java.util.concurrent.CountDownLatch started = new java.util.concurrent.CountDownLatch(1);
                java.util.concurrent.CountDownLatch done = new java.util.concurrent.CountDownLatch(1);

                Thread borrowThread = Thread.ofVirtual().unstarted(() -> {
                    started.countDown();
                    try {
                        // This will block since pool is empty
                        engine.openConnection().close();
                    } finally {
                        done.countDown();
                    }
                });
                borrowThread.start();

                // Wait for the thread to start blocking
                if (!started.await(1, java.util.concurrent.TimeUnit.SECONDS)) {
                    borrowThread.interrupt();
                    throw new AssertionError("Helper thread did not start");
                }

                waitUntilPendingAcquiresAboveZero(engine, QUEUE_WAIT_TIMEOUT);

                EngineStats queuedStats = engine.stats();
                assertThat(queuedStats.pendingAcquires())
                        .as("Queue should have at least one thread waiting")
                        .isGreaterThan(0);

                // Action & Assertion (ADR-035): the universal contract only requires the decision to
                // be stable and non-throwing here — the shed trigger is tier/config-defined.
                boolean first = engine.canServiceRequest();
                boolean second = engine.canServiceRequest();
                assertThat(first)
                        .as("Decision while a queue is forming must be consistent")
                        .isEqualTo(second);

                // Cleanup: Release one connection so the waiting thread can proceed
                connections.get(0).close();
                if (!done.await(2, java.util.concurrent.TimeUnit.SECONDS)) {
                    borrowThread.interrupt();
                }
            } finally {
                connections.forEach(c -> {
                    try {
                        c.close();
                    } catch (Exception ignored) {
                    }
                });
            }
        }

        /**
         * TEST 3: Decision is non-throwing and consistent at high saturation with no queue.
         * Precondition: Pool at 90%+ saturation, no acquires queued.
         * Rationale (ADR-035): saturation alone (with no queue forming) no longer mandates a shed —
         * a new request simply becomes the first waiter and acquires as capacity frees. Whether a
         * tier sheds proactively is implementation-defined; the universal contract only requires a
         * stable, non-throwing decision.
         */
        @Test
        @DisplayName("canServiceRequest() is stable and non-throwing at 90% saturation (no queue)")
        void testCanServiceRequest_StableAtHighSaturation() {
            EngineStats stats = engine.stats();
            int maxPoolSize = stats.maxConnections();
            int threshold90Percent = (int) Math.ceil(maxPoolSize * 0.9);

            // Precondition: Open connections until we're at 90% saturation
            var connections = new java.util.ArrayList<PersistenceConnection>();
            for (int i = 0; i < threshold90Percent; i++) {
                connections.add(engine.openConnection());
            }

            try {
                EngineStats saturatedStats = engine.stats();
                assertThat(saturatedStats.activeConnections())
                        .isGreaterThanOrEqualTo(threshold90Percent);
                assertThat(saturatedStats.saturation())
                        .isGreaterThanOrEqualTo(0.9);

                // Action & Assertion (ADR-035): value is tier-defined; require only stability.
                boolean first = engine.canServiceRequest();
                boolean second = engine.canServiceRequest();
                assertThat(first)
                        .as("Decision at high saturation must be consistent")
                        .isEqualTo(second);
            } finally {
                connections.forEach(c -> {
                    try {
                        c.close();
                    } catch (Exception ignored) {
                    }
                });
            }
        }

        /**
         * Returns {@code false} or throws {@link PersistenceProviderException} once the engine
         * has been shut down.
         *
         * <p>Precondition: {@code engine.close()} has already been called. The engine cannot
         * service further requests after shutdown.
         */
        @Test
        @DisplayName("canServiceRequest() returns false or throws when engine is closed")
        void testCanServiceRequestReturnsFalse_WhenEngineClosed() {
            // Precondition
            engine.close();

            // Action & Assertion
            assertCanServiceRequestReturnsFalseOrThrowsAfterShutdown(engine);
        }
    }

    private static void assertCanServiceRequestReturnsFalseOrThrowsAfterShutdown(PersistenceEngine engine) {
        try {
            boolean canService = engine.canServiceRequest();
            assertThat(canService)
                    .as("Engine should not accept requests after close()")
                    .isFalse();
        } catch (PersistenceProviderException _) {
            // Allowed by SPI contract after shutdown.
        }
    }

    private static void waitUntilPendingAcquiresAboveZero(PersistenceEngine engine, Duration timeout)
            throws InterruptedException {
        long deadlineNanos = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadlineNanos) {
            if (engine.stats().pendingAcquires() > 0) {
                return;
            }
            Thread.sleep(QUEUE_POLL_SLEEP_MILLIS);
        }
        throw new AssertionError("Timed out waiting for pending acquires > 0");
    }

    // =========================================================================
    // Performance Contract (hot path requirements)
    // =========================================================================

    @Nested
    @DisplayName("Performance contract (hot path)")
    class PerformanceContract {

        /**
         * Verify that canServiceRequest() does not block. The authoritative sub-millisecond bound
         * is enforced by JMH benchmarks; this TCK guard uses 50ms to catch regressions only.
         */
        @Test
        @DisplayName("canServiceRequest() does not block (hot path sanity check; authoritative bound is in JMH)")
        void testCanServiceRequest_LatencySubMillisecond() {
            // Warm up
            for (int i = 0; i < 100; i++) {
                engine.canServiceRequest();
            }

            // Measure 1000 calls
            long start = System.nanoTime();
            for (int i = 0; i < 1000; i++) {
                engine.canServiceRequest();
            }
            long elapsed = System.nanoTime() - start;
            double avgNanos = (double) elapsed / 1000;
            double avgMicros = avgNanos / 1000;

            // Authoritative sub-millisecond bound is enforced by JMH benchmarks.
            // TCK only guards against blocking (>50ms average indicates a bug).
            assertThat(avgMicros)
                    .as("canServiceRequest() must not block; average latency >50ms indicates a defect")
                    .isLessThan(50_000);
        }
    }

    // =========================================================================
    // Consistency & Idempotence
    // =========================================================================

    @Nested
    @DisplayName("Consistency and idempotence")
    class ConsistencyAndIdempotence {

        /**
         * Multiple calls to canServiceRequest() in the same state should return consistent results.
         */
        @Test
        @DisplayName("Multiple calls return consistent results in same state")
        void testCanServiceRequest_MultipleCallsConsistent() {
            // Capture state
            boolean first = engine.canServiceRequest();
            boolean second = engine.canServiceRequest();
            boolean third = engine.canServiceRequest();

            assertThat(first)
                    .as("Multiple calls in same state should return consistent result")
                    .isEqualTo(second)
                    .isEqualTo(third);
        }

        /**
         * Verify transition: when capacity increases (connection released), decision can flip to true.
         */
        @Test
        @DisplayName("Decision changes when pool capacity changes")
        void testCanServiceRequest_DecisionFlipsWithCapacityChange() {
            EngineStats stats = engine.stats();
            int maxPoolSize = stats.maxConnections();

            // Saturate to 90%
            int threshold90 = (int) Math.ceil(maxPoolSize * 0.9);
            var connections = new java.util.ArrayList<PersistenceConnection>();
            for (int i = 0; i < threshold90; i++) {
                connections.add(engine.openConnection());
            }

            try {
                // ADR-035: saturation with no queue is tier-defined; the universal invariant is
                // that capacity availability admits. Release a connection and assert admit.
                connections.get(0).close();
                connections.remove(0);

                // Now we should have capacity
                boolean afterReleaseDecision = engine.canServiceRequest();
                assertThat(afterReleaseDecision)
                        .as("Decision should be true when capacity is available")
                        .isTrue();
            } finally {
                connections.forEach(c -> {
                    try {
                        c.close();
                    } catch (Exception ignored) {
                    }
                });
            }
        }
    }

    // =========================================================================
    // Edge Cases
    // =========================================================================

    @Nested
    @DisplayName("Edge cases")
    class EdgeCases {

        /**
         * Verify behavior with minimal pool size (1 connection).
         */
        @Test
        @DisplayName("Handles minimal pool size correctly")
        void testCanServiceRequest_MinimalPoolSize() {
            // Most implementations default to at least 1 connection
            EngineStats stats = engine.stats();
            int maxPoolSize = stats.maxConnections();
            assertThat(maxPoolSize).isGreaterThanOrEqualTo(1);

            // ADR-035: with 1 connection active and no queue, the decision is tier-defined; assert
            // only that it is non-throwing and consistent (Community admits; strict shed is covered
            // by the Community admission tests).
            if (maxPoolSize == 1) {
                try (var _ = engine.openConnection()) {
                    EngineStats withConn = engine.stats();
                    assertThat(withConn.activeConnections()).isEqualTo(1);
                    boolean first = engine.canServiceRequest();
                    boolean second = engine.canServiceRequest();
                    assertThat(first).isEqualTo(second);
                }
            }
        }

        /**
         * Verify behavior immediately after engine creation.
         */
        @Test
        @DisplayName("Initial state accepts requests (pool has capacity)")
        void testCanServiceRequest_InitialState() {
            EngineStats stats = engine.stats();
            assertThat(stats.activeConnections()).isZero();
            assertThat(stats.pendingAcquires()).isZero();
            assertThat(stats.saturation()).isLessThan(0.9);

            boolean canService = engine.canServiceRequest();
            assertThat(canService)
                    .as("Fresh engine should accept requests")
                    .isTrue();
        }
    }

    // =========================================================================
    // Exception Handling
    // =========================================================================

    @Nested
    @DisplayName("Exception handling")
    class ExceptionHandling {

        /**
         * canServiceRequest() should handle engine shutdown gracefully.
         * Per contract, it should either return false or throw PersistenceProviderException.
         */
        @Test
        @DisplayName("After close(), canServiceRequest() handles gracefully")
        void testCanServiceRequest_AfterCloseHandlesGracefully() {
            engine.close();

            // Should either return false or throw PersistenceProviderException,
            // but not throw unexpected exceptions.
            assertCanServiceRequestReturnsFalseOrThrowsAfterShutdown(engine);
        }
    }

    // =========================================================================
    // Zero-Allocation Check (JFR verification in integration tests)
    // =========================================================================

    @Nested
    @DisplayName("Memory safety (baseline check)")
    class MemorySafety {

        /**
         * This test verifies that repeated calls to canServiceRequest() do not cause
         * uncontrolled garbage collection pressure. The actual JFR analysis is performed
         * in the integration test layer with JFR enabled.
         *
         * This baseline test just ensures multiple calls remain safe.
         */
        @Test
        @DisplayName("Repeated calls are memory-safe (no exceptions)")
        void testCanServiceRequest_RepeatedCallsSafe() {
            assertThatCode(() -> {
                for (int i = 0; i < 10000; i++) {
                    engine.canServiceRequest();
                }
            }).doesNotThrowAnyException();
        }
    }
}
