/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.spi.events;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * L0 Contract: {@link EventEngineStats} — Valhalla-readiness and derived metric correctness.
 *
 * <h2>Valhalla-Readiness</h2>
 * <p>All fields are primitives. Structural equals/hashCode verified.
 * {@code ZERO} sentinel and derived metrics {@code queueUtilization()} / {@code errorRate()}
 * tested for edge-case division-by-zero guards.
 *
 * @since 0.5.0
 */
@DisplayName("L0: EventEngineStats — Valhalla-readiness + derived metrics")
class EventEngineStatsTest {

    // =========================================================================
    // ZERO sentinel
    // =========================================================================

    @Nested
    @DisplayName("ZERO sentinel — pre-start default")
    class ZeroSentinel {

        @Test
        @DisplayName("ZERO has all numeric fields at 0 and loopRunning=false")
        void zeroFields() {
            EventEngineStats z = EventEngineStats.ZERO;
            assertThat(z.publishedTotal()).isZero();
            assertThat(z.processedTotal()).isZero();
            assertThat(z.failedTotal()).isZero();
            assertThat(z.queueDepth()).isZero();
            assertThat(z.queueCapacity()).isZero();
            assertThat(z.avgDispatchNanos()).isZero();
            assertThat(z.loopRunning()).isFalse();
        }

        @Test
        @DisplayName("queueUtilization() on ZERO == 0.0 — no division by zero")
        void queueUtilizationOnZeroIsSafe() {
            assertThat(EventEngineStats.ZERO.queueUtilization()).isZero();
        }

        @Test
        @DisplayName("errorRate() on ZERO == 0.0 — no division by zero")
        void errorRateOnZeroIsSafe() {
            assertThat(EventEngineStats.ZERO.errorRate()).isZero();
        }
    }

    // =========================================================================
    // Constructor and field access
    // =========================================================================

    @Nested
    @DisplayName("Constructor — fields stored and readable")
    class ConstructorContract {

        @Test
        @DisplayName("All constructor fields are readable via accessors")
        void allFieldsReadable() {
            EventEngineStats s = new EventEngineStats(100L, 95L, 2L, 10L, 1000L, 350L, true);
            assertThat(s.publishedTotal()).isEqualTo(100L);
            assertThat(s.processedTotal()).isEqualTo(95L);
            assertThat(s.failedTotal()).isEqualTo(2L);
            assertThat(s.queueDepth()).isEqualTo(10L);
            assertThat(s.queueCapacity()).isEqualTo(1000L);
            assertThat(s.avgDispatchNanos()).isEqualTo(350L);
            assertThat(s.loopRunning()).isTrue();
        }
    }

    // =========================================================================
    // Derived metrics
    // =========================================================================

    @Nested
    @DisplayName("Derived metrics — queueUtilization() + errorRate()")
    class DerivedMetrics {

        @Test
        @DisplayName("queueUtilization() = queueDepth / queueCapacity")
        void queueUtilizationNominal() {
            EventEngineStats s = new EventEngineStats(0L, 0L, 0L, 250L, 1000L, 0L, true);
            assertThat(s.queueUtilization()).isCloseTo(0.25, within(1e-9));
        }

        @Test
        @DisplayName("queueUtilization() = 1.0 when queue is full")
        void queueFullUtilization() {
            EventEngineStats s = new EventEngineStats(0L, 0L, 0L, 1000L, 1000L, 0L, true);
            assertThat(s.queueUtilization()).isCloseTo(1.0, within(1e-9));
        }

        @Test
        @DisplayName("queueUtilization() = 0.0 when queueCapacity == 0 — no division by zero")
        void queueUtilizationZeroCapacity() {
            EventEngineStats s = new EventEngineStats(0L, 0L, 0L, 0L, 0L, 0L, false);
            assertThat(s.queueUtilization()).isZero();
        }

        @Test
        @DisplayName("errorRate() = failedTotal / processedTotal")
        void errorRateNominal() {
            EventEngineStats s = new EventEngineStats(100L, 100L, 5L, 0L, 0L, 0L, true);
            assertThat(s.errorRate()).isCloseTo(0.05, within(1e-9));
        }

        @Test
        @DisplayName("errorRate() = 0.0 when processedTotal == 0 — no division by zero")
        void errorRateZeroProcessed() {
            EventEngineStats s = new EventEngineStats(0L, 0L, 0L, 0L, 0L, 0L, false);
            assertThat(s.errorRate()).isZero();
        }

        @Test
        @DisplayName("errorRate() = 1.0 when all processed events failed")
        void errorRateAllFailed() {
            EventEngineStats s = new EventEngineStats(50L, 50L, 50L, 0L, 0L, 0L, true);
            assertThat(s.errorRate()).isCloseTo(1.0, within(1e-9));
        }
    }

    // =========================================================================
    // Valhalla-readiness: structural equals / hashCode
    // =========================================================================

    @Nested
    @DisplayName("Valhalla-readiness — structural equals/hashCode")
    class ValhallaReadiness {

        @Test
        @DisplayName("Two structurally equal EventEngineStats are equal")
        void structuralEquality() {
            EventEngineStats a = new EventEngineStats(10L, 9L, 1L, 5L, 100L, 200L, true);
            EventEngineStats b = new EventEngineStats(10L, 9L, 1L, 5L, 100L, 200L, true);
            assertThat(a).isEqualTo(b);
        }

        @Test
        @DisplayName("Equal stats have same hashCode")
        void structuralHashCode() {
            EventEngineStats a = new EventEngineStats(10L, 9L, 1L, 5L, 100L, 200L, true);
            EventEngineStats b = new EventEngineStats(10L, 9L, 1L, 5L, 100L, 200L, true);
            assertThat(a).hasSameHashCodeAs(b);
        }

        @Test
        @DisplayName("loopRunning field participates in equals")
        void loopRunningParticipatesInEquals() {
            EventEngineStats a = new EventEngineStats(0L, 0L, 0L, 0L, 0L, 0L, true);
            EventEngineStats b = new EventEngineStats(0L, 0L, 0L, 0L, 0L, 0L, false);
            assertThat(a).isNotEqualTo(b);
        }

        @Test
        @DisplayName("equals(null) returns false")
        void equalsNullReturnsFalse() {
            assertThat(EventEngineStats.ZERO.equals(null)).isFalse();
        }

        @Test
        @DisplayName("equals() is reflexive")
        void equalsReflexive() {
            EventEngineStats s = new EventEngineStats(1L, 1L, 0L, 0L, 10L, 50L, true);
            assertThat(s).isEqualTo(s);
        }
    }
}

