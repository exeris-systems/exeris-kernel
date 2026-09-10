/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.persistence;

import eu.exeris.kernel.spi.persistence.PersistenceConfig;
import eu.exeris.kernel.spi.persistence.PersistenceEngine;
import eu.exeris.kernel.spi.persistence.PersistenceProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * L1 Unit: {@link CommunityPersistenceEngine#canServiceRequest()} — admission control contract.
 *
 * <h2>Coverage</h2>
 * <ul>
 *   <li>Returns {@code true} when pool has available idle connections</li>
 *   <li>Returns {@code false} when no idle connections AND queue is forming (pending acquires > 0)</li>
 *   <li>Returns {@code false} when active connections >= max pool size * 0.9 (90% saturated)</li>
 *   <li>Returns {@code false} when engine is closed</li>
 * </ul>
 *
 */
@DisplayName("L1 Unit: CommunityPersistenceEngine#canServiceRequest()")
class CommunityPersistenceEngineCanServiceRequestTest {

    /**
     * Creates an H2 in-memory test database with the given max pool size.
     */
    private static PersistenceEngine createTestEngine(int maxPoolSize) {
        int minIdleConnections = Math.min(2, maxPoolSize);
        PersistenceConfig config = PersistenceConfig.production(
                "jdbc:h2:mem:exeris_test_" + System.nanoTime() + ";DB_CLOSE_DELAY=-1",
                "sa",
                "",
                maxPoolSize,        // maxPoolSize
            minIdleConnections, // minIdleConnections
                1                   // maxTenantPools
        );

        PersistenceProvider provider = new CommunityPersistenceProvider();
        return provider.createEngine(config);
    }

    @Nested
    @DisplayName("Admission control gate behavior")
    class AdmissionControlGate {

        @Test
        @DisplayName("Returns true when pool has idle connections")
        void returnsTrue_whenPoolHasIdleConnections() {
            try (PersistenceEngine engine = createTestEngine(4)) {
                // Pool should have idle connections available
                assertThat(engine.canServiceRequest()).isTrue();
                assertThat(engine.stats().idleConnections()).isGreaterThan(0);
            }
        }

        @Test
        @DisplayName("ADR-035: admits at 100% saturation when no queue is forming")
        void admits_whenSaturatedButNoQueue() {
            try (PersistenceEngine engine = createTestEngine(4)) {
                // Pool size = 4, fully drained, but NO acquires are queued.
                try (var conn1 = engine.openConnection();
                     var conn2 = engine.openConnection();
                     var conn3 = engine.openConnection();
                     var conn4 = engine.openConnection()) {
                    var stats = engine.stats();
                    assertThat(stats.activeConnections()).isEqualTo(4);
                    assertThat(stats.saturation()).isGreaterThanOrEqualTo(0.9);
                    assertThat(stats.pendingAcquires()).isZero();
                    // ADR-035: saturation alone (no queue) no longer sheds — a new request becomes
                    // the first waiter and acquires as capacity frees. Shedding only kicks in once
                    // the queue exceeds the pool-scaled allowance (covered by the fairness tests).
                    assertThat(engine.canServiceRequest()).isTrue();
                }
            }
        }

        @Test
        @DisplayName("Returns true when pool has capacity below 90% threshold")
        void returnsTrue_whenPoolBelowThreshold() {
            try (PersistenceEngine engine = createTestEngine(4)) {
                // Take 2 connections out of 4 (50% saturation)
                try (var conn1 = engine.openConnection();
                     var conn2 = engine.openConnection()) {
                    var stats = engine.stats();
                    assertThat(stats.activeConnections()).isEqualTo(2);
                    assertThat(stats.saturation()).isLessThan(0.9);
                    // Should accept new requests
                    assertThat(engine.canServiceRequest()).isTrue();
                }
            }
        }

        @Test
        @DisplayName("Returns true at 85% saturation (below 90% threshold)")
        void returnsTrue_at85PercentSaturation() {
            try (PersistenceEngine engine = createTestEngine(4)) {
                // Take 3 connections out of 4 (75% saturation, below 90% = 3.6)
                try (var conn1 = engine.openConnection();
                     var conn2 = engine.openConnection();
                     var conn3 = engine.openConnection()) {
                    var stats = engine.stats();
                    assertThat(stats.activeConnections()).isEqualTo(3);
                    // 3/4 = 0.75 = 75% < 90%
                    assertThat(stats.saturation()).isLessThan(0.9);
                    // At this level, should still accept
                    assertThat(engine.canServiceRequest()).isTrue();
                }
            }
        }

        @Test
        @DisplayName("Returns false when engine is closed")
        void returnsFalse_whenEngineClosed() {
            PersistenceEngine engine = createTestEngine(4);
            engine.close();

            // After close, should not be willing to service requests
            assertThat(engine.canServiceRequest()).isFalse();
        }

        @Test
        @DisplayName("Stats() reflects current pool state")
        void statsReflectsPoolState() {
            try (PersistenceEngine engine = createTestEngine(4)) {
                var initialStats = engine.stats();
                assertThat(initialStats.activeConnections()).isZero();
                assertThat(initialStats.idleConnections()).isGreaterThan(0);

                try (var conn = engine.openConnection()) {
                    var statsWithConn = engine.stats();
                    assertThat(statsWithConn.activeConnections()).isGreaterThan(initialStats.activeConnections());
                }

                var statsAfterClose = engine.stats();
                assertThat(statsAfterClose.activeConnections()).isZero();
            }
        }
    }

    @Nested
    @DisplayName("Edge cases")
    class EdgeCases {

        @Test
        @DisplayName("ADR-035: small pool (1) at 100% with no queue still admits")
        void handlesSmallPoolSize() {
            try (PersistenceEngine engine = createTestEngine(1)) {
                try (var conn = engine.openConnection()) {
                    var stats = engine.stats();
                    assertThat(stats.maxConnections()).isEqualTo(1);
                    assertThat(stats.pendingAcquires()).isZero();
                    // ADR-035: full pool with no queue admits (the next request just queues briefly).
                    assertThat(engine.canServiceRequest()).isTrue();
                }
            }
        }

        @Test
        @DisplayName("Multiple calls to canServiceRequest() return consistent results")
        void consistentResults() {
            try (PersistenceEngine engine = createTestEngine(4)) {
                boolean first = engine.canServiceRequest();
                boolean second = engine.canServiceRequest();
                boolean third = engine.canServiceRequest();

                assertThat(first).isEqualTo(second).isEqualTo(third);
            }
        }
    }
}
