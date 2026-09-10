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
 * L1 Unit: {@link CommunityPersistenceEngine#canServiceRequest()} with fairness control.
 *
 * @since 0.5.0
 */
@DisplayName("L1 Unit: CommunityPersistenceEngine#canServiceRequest() with Fairness")
class CommunityPersistenceEngineFairnessTest {

    private static PersistenceEngine createTestEngine(int maxPoolSize) {
        int minIdleConnections = Math.min(2, maxPoolSize);
        PersistenceConfig config = PersistenceConfig.production(
                "jdbc:h2:mem:exeris_test_" + System.nanoTime() + ";DB_CLOSE_DELAY=-1",
                "sa",
                "",
                maxPoolSize,
                minIdleConnections,
                1
        );
        PersistenceProvider provider = new CommunityPersistenceProvider();
        return provider.createEngine(config);
    }

    private static CommunityPersistenceEngine createCommunityTestEngine(int maxPoolSize) {
        return (CommunityPersistenceEngine) createTestEngine(maxPoolSize);
    }

    @SuppressWarnings("unused")
    @Nested
    @DisplayName("Fairness-aware admission control")
    class FairnessAwareAdmissionControl {

        @Test
        @DisplayName("Returns true when pool has idle capacity")
        void returnsTrue_whenIdleCapacity() {
            try (PersistenceEngine engine = createTestEngine(4)) {
                assertThat(engine.canServiceRequest()).isTrue();
            }
        }

        @Test
        @DisplayName("Returns false when engine is closed")
        void returnsFalse_whenEngineClosed() {
            PersistenceEngine engine = createTestEngine(4);
            engine.close();
            assertThat(engine.canServiceRequest()).isFalse();
        }

        @Test
        @DisplayName("Emits JFR event on every canServiceRequest call")
        void emitsJfrEvent_onCanServiceRequest() {
            try (PersistenceEngine engine = createTestEngine(4)) {
                for (int i = 0; i < 10; i++) {
                    boolean result = engine.canServiceRequest();
                    assertThat(result).isTrue();
                }
            }
        }

        @Test
        @DisplayName("Maintains acceptance rate under normal load")
        void maintainsAcceptanceRate() {
            try (PersistenceEngine engine = createTestEngine(8)) {
                int accepted = 0;
                int total = 100;

                for (int i = 0; i < total; i++) {
                    if (engine.canServiceRequest()) {
                        accepted++;
                    }
                }

                double acceptanceRate = (double) accepted / (double) total;
                assertThat(acceptanceRate).isGreaterThanOrEqualTo(0.80);
            }
        }

        // ADR-035: the deterministic reject-reason machine still fires under a STRICT allowance
        // (queueDepthAllowanceRatio=0 restores the pre-035 "shed on first queued acquire" contract).
        // These tests pin the reason-pairing logic against the controller directly so they do not
        // depend on the process-global CommunityAdmissionConfig.CURRENT.
        private static final CommunityAdmissionConfig STRICT = CommunityAdmissionConfig.STRICT;

        @Test
        @DisplayName("STRICT: rejects in guard band when queued with low headroom (REJECT_GUARD_BAND_FAIRNESS)")
        void rejectsInGuardBand_whenQueuedAndLowHeadroom() {
            CommunityPersistenceAdmissionController controller = new CommunityPersistenceAdmissionController();
            // (active=17, idle=0, queued=3, max=20): sat=0.85 (guard band), headroom=3 => early reject.
            CommunityHikariSupport.AdmissionSnapshot guardBandSnapshot =
                    new CommunityHikariSupport.AdmissionSnapshot(17, 0, 3, 20);

            assertThat(controller.canServiceRequest(guardBandSnapshot, false, STRICT)).isFalse();
            assertThat(controller.decisionReason(guardBandSnapshot, false, STRICT))
                    .isEqualTo("REJECT_GUARD_BAND_FAIRNESS");
        }

        @Test
        @DisplayName("STRICT: deterministic REJECT_HARD_SATURATION at >=90% saturation with a queue")
        void rejectsHardSaturation_withDeterministicReason() {
            CommunityPersistenceAdmissionController controller = new CommunityPersistenceAdmissionController();
            // (active=9, idle=0, queued=2, max=10): sat=0.90 (hard), queued > allowance(0) => reject.
            CommunityHikariSupport.AdmissionSnapshot snapshot =
                    new CommunityHikariSupport.AdmissionSnapshot(9, 0, 2, 10);

            assertThat(controller.canServiceRequest(snapshot, false, STRICT)).isFalse();
            assertThat(controller.decisionReason(snapshot, false, STRICT)).isEqualTo("REJECT_HARD_SATURATION");
        }

        @Test
        @DisplayName("STRICT: deterministic REJECT_NO_CAPACITY when queue forms below guard band")
        void rejectsNoCapacity_withDeterministicReason() {
            CommunityPersistenceAdmissionController controller = new CommunityPersistenceAdmissionController();
            // (active=2, idle=0, queued=4, max=10): sat=0.20 (below guard band), queued > allowance(0).
            CommunityHikariSupport.AdmissionSnapshot snapshot =
                    new CommunityHikariSupport.AdmissionSnapshot(2, 0, 4, 10);

            assertThat(controller.canServiceRequest(snapshot, false, STRICT)).isFalse();
            assertThat(controller.decisionReason(snapshot, false, STRICT)).isEqualTo("REJECT_NO_CAPACITY");
        }

        @Test
        @DisplayName("Deterministic REJECT_ENGINE_CLOSED reason when engine is closed")
        void rejectsEngineClosed_withDeterministicReason() {
            CommunityPersistenceAdmissionController controller = new CommunityPersistenceAdmissionController();
            CommunityHikariSupport.AdmissionSnapshot snapshot =
                    new CommunityHikariSupport.AdmissionSnapshot(0, 0, 0, 4);

            assertThat(controller.canServiceRequest(snapshot, true, STRICT)).isFalse();
            assertThat(controller.decisionReason(snapshot, true, STRICT)).isEqualTo("REJECT_ENGINE_CLOSED");
        }

        @Test
        @DisplayName("Deterministic ACCEPT reason when capacity is available")
        void accepts_withDeterministicReason() {
            CommunityPersistenceEngine engine = createCommunityTestEngine(10);
            try {
                CommunityHikariSupport.AdmissionSnapshot snapshot =
                        new CommunityHikariSupport.AdmissionSnapshot(2, 4, 0, 10);

                assertThat(engine.canServiceRequest(snapshot)).isTrue();
                assertThat(engine.decisionReason(snapshot)).isEqualTo("ACCEPT");
            } finally {
                engine.close();
            }
        }

        // ADR-035 regression guard: this is the constrained-benchmark scenario. A tiny pool
        // (max=2 under -XX:ActiveProcessorCount=1) with a transient queue from a high client
        // count must ADMIT (deferring to the acquire timeout) rather than shed — restoring the
        // pre-v0.6.0 0% error rate. allowance = ceil(2 * 8.0) = 16, so queued <= 16 is admitted.
        @Test
        @DisplayName("DEFAULT: small saturated pool with transient queue admits (benchmark regression guard)")
        void defaultConfig_smallPoolTransientQueue_admits() {
            CommunityPersistenceAdmissionController controller = new CommunityPersistenceAdmissionController();
            // (active=2, idle=0, queued=14, max=2): pool full, 14 waiting — 16-conn wrk burst, 2-slot pool.
            CommunityHikariSupport.AdmissionSnapshot burst =
                    new CommunityHikariSupport.AdmissionSnapshot(2, 0, 14, 2);

            assertThat(controller.canServiceRequest(burst, false, CommunityAdmissionConfig.DEFAULT)).isTrue();
            assertThat(controller.decisionReason(burst, false, CommunityAdmissionConfig.DEFAULT))
                    .isEqualTo("ACCEPT");
        }

        // ADR-035: backpressure is still real — once the queue exceeds the pool-scaled allowance,
        // even the default config sheds.
        @Test
        @DisplayName("DEFAULT: queue beyond the pool-scaled allowance still sheds (backpressure intact)")
        void defaultConfig_deepQueue_sheds() {
            CommunityPersistenceAdmissionController controller = new CommunityPersistenceAdmissionController();
            // (active=2, idle=0, queued=20, max=2): pool full, 20 waiting > allowance(16) => shed.
            CommunityHikariSupport.AdmissionSnapshot deep =
                    new CommunityHikariSupport.AdmissionSnapshot(2, 0, 20, 2);

            assertThat(controller.canServiceRequest(deep, false, CommunityAdmissionConfig.DEFAULT)).isFalse();
            assertThat(controller.decisionReason(deep, false, CommunityAdmissionConfig.DEFAULT))
                    .isEqualTo("REJECT_HARD_SATURATION");
        }
    }

    @SuppressWarnings("unused")
    @Nested
    @DisplayName("Edge cases")
    class EdgeCases {

        @Test
        @DisplayName("Handles pool with max size 1")
        void handlesMinimalPool() {
            try (PersistenceEngine engine = createTestEngine(1)) {
                assertThat(engine.canServiceRequest()).isTrue();

                try (var conn = engine.openConnection()) {
                    assertThat(conn.isOpen()).isTrue();
                    var stats = engine.stats();
                    double saturation = (double) stats.activeConnections() / (double) stats.maxConnections();
                    assertThat(saturation).isGreaterThanOrEqualTo(1.0);
                }
            }
        }

        @Test
        @DisplayName("Handles saturation scenario (3+ of 4 connections active)")
        void handlesSaturation() {
            try (PersistenceEngine engine = createTestEngine(4)) {
                try (var conn1 = engine.openConnection();
                     var conn2 = engine.openConnection();
                     var conn3 = engine.openConnection()) {

                    assertThat(conn1.isOpen()).isTrue();
                    assertThat(conn2.isOpen()).isTrue();
                    assertThat(conn3.isOpen()).isTrue();

                    var stats = engine.stats();
                    double saturation = (double) stats.activeConnections() / (double) stats.maxConnections();

                    if (saturation >= 0.85) {
                        boolean result = engine.canServiceRequest();
                        assertThat(result).isFalse();
                    }
                }
            }
        }
    }
}
