/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.tck.contract.persistence;

import eu.exeris.kernel.spi.persistence.EngineStats;
import eu.exeris.kernel.spi.persistence.PersistenceConnection;
import eu.exeris.kernel.spi.persistence.PersistenceEngine;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * TCK: Abstract contract for {@link PersistenceEngine#canServiceRequest()} admission control.
 *
 * <h2>Contract Semantics</h2>
 * <p>Per ADR-010, admission control gates prevent thread park storms when the underlying
 * connection pool is saturated. This TCK verifies that:
 * <ul>
 *   <li>{@code canServiceRequest()} returns {@code true} when pool has available capacity</li>
 *   <li>{@code canServiceRequest()} returns {@code false} when pool is forming a queue</li>
 *   <li>{@code canServiceRequest()} returns {@code false} when pool is near saturation (&gt;90%)</li>
 *   <li>{@code canServiceRequest()} returns {@code false} after engine shutdown</li>
 *   <li>Admission gate is fast (&lt;1ms) on hot path (zero-alloc guarantee)</li>
 *   <li>Multiple calls to {@code canServiceRequest()} are consistent</li>
 * </ul>
 *
 * <h2>Tier-Specific Heuristics</h2>
 * <p>Community tier (HikariCP):
 * <ul>
 *   <li>Returns {@code false} if idle == 0 AND queued > 0 (prevent cascade)</li>
 *   <li>Returns {@code false} if active >= (max * 0.9) (conservative 10% buffer)</li>
 *   <li>Returns {@code true} otherwise</li>
 * </ul>
 *
 * <p>Enterprise tier may implement more sophisticated predictive gating based on native
 * driver hints (e.g., io_uring SQE availability).
 *
 */
public abstract class AbstractPersistenceEngineAdmissionControlTck {

    /**
     * Creates a fully bootstrapped {@link PersistenceEngine} for testing.
     * Implementations should use a fixed max pool size (recommended: 4) for deterministic testing.
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
         * TEST 1: Returns true when pool has idle connections
         * Precondition: Pool is fresh, has idle connections
         * Rationale: Pool can accept new request without saturation risk
         */
        @Test
        @DisplayName("canServiceRequest() returns true when pool has idle connections")
        void testCanServiceRequestReturnsTrue_WhenPoolHasIdleConnections() {
            // Precondition: Fresh pool should have idle connections available
            EngineStats initialStats = engine.stats();
            assertThat(initialStats.idleConnections()).isGreaterThan(0);
            assertThat(initialStats.activeConnections()).isZero();

            // Action
            boolean canService = engine.canServiceRequest();

            // Assertion
            assertThat(canService)
                    .as("Pool with idle connections should accept new requests")
                    .isTrue();
        }

        /**
         * TEST 2: Returns false when idle == 0 AND queued > 0
         * Precondition: Pool saturated (active == max, idle == 0) with threads queued
         * Rationale: Queue forming indicates pool cannot accept new work; accepting would cascade
         */
        @Test
        @DisplayName("canServiceRequest() returns false when idle zero and queue forming")
        void testCanServiceRequestReturnsFalse_WhenIdleZeroAndQueued() throws InterruptedException {
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

                // Give the thread a moment to actually block in the queue
                Thread.sleep(50);

                EngineStats queuedStats = engine.stats();
                assertThat(queuedStats.pendingAcquires())
                        .as("Queue should have at least one thread waiting")
                        .isGreaterThan(0);

                // Action: Check if canServiceRequest() rejects when queue is forming
                boolean canService = engine.canServiceRequest();

                // Assertion
                assertThat(canService)
                        .as("Pool should reject new requests when queue is forming (idle==0 AND queued>0)")
                        .isFalse();

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
         * TEST 3: Returns false when active >= (max * 0.9)
         * Precondition: Pool at 90%+ saturation
         * Rationale: Proactive rejection prevents pushing pool to absolute limits
         */
        @Test
        @DisplayName("canServiceRequest() returns false when active at 90% saturation")
        void testCanServiceRequestReturnsFalse_WhenActiveNear90Percent() {
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

                // Action
                boolean canService = engine.canServiceRequest();

                // Assertion
                assertThat(canService)
                        .as("Pool should conservatively reject requests at 90%+ saturation")
                        .isFalse();
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
         * TEST 4: Returns false after engine shutdown
         * Precondition: Engine.close() has been called
         * Rationale: Cannot service requests after shutdown
         */
        @Test
        @DisplayName("canServiceRequest() returns false when engine is closed")
        void testCanServiceRequestReturnsFalse_WhenEngineClosed() {
            // Precondition
            engine.close();

            // Action & Assertion
            // Should either return false or throw PersistenceProviderException
            assertThat(engine.canServiceRequest())
                    .as("Engine should not accept requests after close()")
                    .isFalse();
        }
    }

    // =========================================================================
    // Performance Contract (hot path requirements)
    // =========================================================================

    @Nested
    @DisplayName("Performance contract (hot path)")
    class PerformanceContract {

        /**
         * Verify that canServiceRequest() is fast on the hot path (<=1ms single call).
         * This test allows for millisecond-level variance but should be consistently sub-millisecond.
         */
        @Test
        @DisplayName("canServiceRequest() latency is sub-millisecond (hot path)")
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

            assertThat(avgMicros)
                    .as("Average latency should be <1000 micros (1ms)")
                    .isLessThan(1000);
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
                boolean saturatedDecision = engine.canServiceRequest();
                assertThat(saturatedDecision).isFalse();

                // Release connections
                connections.get(0).close();
                connections.remove(0);

                // Now we should have capacity
                boolean afterReleaseDecision = engine.canServiceRequest();
                assertThat(afterReleaseDecision)
                        .as("Decision should flip to true after releasing connections")
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

            // With 1 connection, reaching 90% means that 1 active connection rejects
            if (maxPoolSize == 1) {
                try (var conn = engine.openConnection()) {
                    EngineStats withConn = engine.stats();
                    assertThat(withConn.activeConnections()).isEqualTo(1);
                    boolean canService = engine.canServiceRequest();
                    assertThat(canService).isFalse();
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
            assertThat(stats.idleConnections()).isGreaterThan(0);

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

            // Should either return false or throw (but not NPE or other unexpected exceptions)
            assertThatCode(() -> {
                boolean result = engine.canServiceRequest();
                // If we get here, result must be false
                assertThat(result).isFalse();
            }).doesNotThrowAnyException();
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
