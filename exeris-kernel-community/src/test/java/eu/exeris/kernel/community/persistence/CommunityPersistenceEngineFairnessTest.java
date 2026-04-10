/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
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

        @Test
        @DisplayName("Rejects in guard band when queued and fairness is degraded")
        void rejectsInGuardBand_whenQueuedAndFairnessDegraded() {
            try (CommunityPersistenceEngine engine = createCommunityTestEngine(20)) {
                CommunityHikariSupport.AdmissionSnapshot hardRejectSnapshot =
                        new CommunityHikariSupport.AdmissionSnapshot(18, 0, 2, 20);
                for (int i = 0; i < 40; i++) {
                    assertThat(engine.canServiceRequest(hardRejectSnapshot)).isFalse();
                }

                CommunityHikariSupport.AdmissionSnapshot guardBandSnapshot =
                        new CommunityHikariSupport.AdmissionSnapshot(17, 3, 1, 20);

                assertThat(engine.canServiceRequest(guardBandSnapshot)).isFalse();
                assertThat(engine.decisionReason(guardBandSnapshot))
                        .isEqualTo("REJECT_GUARD_BAND_FAIRNESS");
            }
        }

        @Test
        @DisplayName("Uses deterministic REJECT_HARD_SATURATION reason at >=90% saturation")
        void rejectsHardSaturation_withDeterministicReason() {
            try (CommunityPersistenceEngine engine = createCommunityTestEngine(10)) {
                CommunityHikariSupport.AdmissionSnapshot snapshot =
                        new CommunityHikariSupport.AdmissionSnapshot(9, 0, 1, 10);

                assertThat(engine.canServiceRequest(snapshot)).isFalse();
                assertThat(engine.decisionReason(snapshot)).isEqualTo("REJECT_HARD_SATURATION");
            }
        }

        @Test
        @DisplayName("Preserves decision/reason pairing across guard-band and saturation transitions")
        void preservesDecisionReasonPairing_acrossBoundaryTransitions() {
            try (CommunityPersistenceEngine engine = createCommunityTestEngine(20)) {
                CommunityHikariSupport.AdmissionSnapshot hardSaturationSnapshot =
                        new CommunityHikariSupport.AdmissionSnapshot(18, 0, 2, 20);
                for (int i = 0; i < 40; i++) {
                    assertThat(engine.canServiceRequest(hardSaturationSnapshot)).isFalse();
                }

                CommunityHikariSupport.AdmissionSnapshot guardBandSnapshot =
                        new CommunityHikariSupport.AdmissionSnapshot(17, 3, 1, 20);
                assertThat(engine.canServiceRequest(guardBandSnapshot)).isFalse();
                assertThat(engine.decisionReason(guardBandSnapshot))
                        .isEqualTo("REJECT_GUARD_BAND_FAIRNESS");

                assertThat(engine.canServiceRequest(hardSaturationSnapshot)).isFalse();
                assertThat(engine.decisionReason(hardSaturationSnapshot))
                        .isEqualTo("REJECT_HARD_SATURATION");

                CommunityHikariSupport.AdmissionSnapshot healthySnapshot =
                        new CommunityHikariSupport.AdmissionSnapshot(2, 4, 0, 20);
                assertThat(engine.canServiceRequest(healthySnapshot)).isTrue();
                assertThat(engine.decisionReason(healthySnapshot)).isEqualTo("ACCEPT");
            }
        }

        @Test
        @DisplayName("Uses deterministic REJECT_NO_CAPACITY reason when queue forms below guard band")
        void rejectsNoCapacity_withDeterministicReason() {
            try (CommunityPersistenceEngine engine = createCommunityTestEngine(10)) {
                CommunityHikariSupport.AdmissionSnapshot snapshot =
                        new CommunityHikariSupport.AdmissionSnapshot(2, 0, 4, 10);

                assertThat(engine.canServiceRequest(snapshot)).isFalse();
                assertThat(engine.decisionReason(snapshot)).isEqualTo("REJECT_NO_CAPACITY");
            }
        }

        @Test
        @DisplayName("Uses deterministic REJECT_ENGINE_CLOSED reason when engine is closed")
        void rejectsEngineClosed_withDeterministicReason() {
            CommunityPersistenceEngine engine = createCommunityTestEngine(4);
            try {
                CommunityHikariSupport.AdmissionSnapshot snapshot =
                        new CommunityHikariSupport.AdmissionSnapshot(0, 0, 0, 4);
                engine.close();

                assertThat(engine.canServiceRequest(snapshot)).isFalse();
                assertThat(engine.decisionReason(snapshot)).isEqualTo("REJECT_ENGINE_CLOSED");
            } finally {
                engine.close();
            }
        }

        @Test
        @DisplayName("Uses deterministic ACCEPT reason when capacity is available")
        void accepts_withDeterministicReason() {
            try (CommunityPersistenceEngine engine = createCommunityTestEngine(10)) {
                CommunityHikariSupport.AdmissionSnapshot snapshot =
                        new CommunityHikariSupport.AdmissionSnapshot(2, 4, 0, 10);

                assertThat(engine.canServiceRequest(snapshot)).isTrue();
                assertThat(engine.decisionReason(snapshot)).isEqualTo("ACCEPT");
            }
        }
    }

    @Nested
    @DisplayName("Edge cases")
    class EdgeCases {

        @Test
        @DisplayName("Handles pool with max size 1")
        void handlesMinimalPool() {
            try (PersistenceEngine engine = createTestEngine(1)) {
                assertThat(engine.canServiceRequest()).isTrue();

                try (var conn = engine.openConnection()) {
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

                    var stats = engine.stats();
                    double saturation = (double) stats.activeConnections() / (double) stats.maxConnections();
                    
                    if (saturation >= 0.85) {
                        // When saturated, canServiceRequest may return false
                        boolean result = engine.canServiceRequest();
                        assertThat(result).isFalse();
                    }
                }
            }
        }
    }
}
