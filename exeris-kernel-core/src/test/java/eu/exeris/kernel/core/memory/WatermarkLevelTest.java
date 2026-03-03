/*
 * Copyright (C) 2025-2026 Exeris. All rights reserved.
 *
 * This code is part of the Exeris Systems.
 * Distributed under the proprietary Exeris Software License.
 * Unauthorized copying or distribution is prohibited.
 */
package eu.exeris.kernel.core.memory;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * L1 Unit Tests: {@link WatermarkLevel} — threshold classification and transition semantics.
 *
 * @since 0.5.0
 */
@DisplayName("L1: WatermarkLevel")
class WatermarkLevelTest {

    @Nested
    @DisplayName("forUtilization — level classification")
    class ForUtilization {

        @Test
        @DisplayName("0.0 → NORMAL")
        void zeroIsNormal() {
            assertThat(WatermarkLevel.forUtilization(0.0)).isEqualTo(WatermarkLevel.NORMAL);
        }

        @Test
        @DisplayName("0.5 → NORMAL (below 70%)")
        void fiftyPercentIsNormal() {
            assertThat(WatermarkLevel.forUtilization(0.5)).isEqualTo(WatermarkLevel.NORMAL);
        }

        @Test
        @DisplayName("0.6999 → NORMAL (just below warning threshold)")
        void justBelowWarning() {
            assertThat(WatermarkLevel.forUtilization(0.6999)).isEqualTo(WatermarkLevel.NORMAL);
        }

        @Test
        @DisplayName("0.70 → WARNING (exact threshold)")
        void exactWarningThreshold() {
            assertThat(WatermarkLevel.forUtilization(0.70)).isEqualTo(WatermarkLevel.WARNING);
        }

        @Test
        @DisplayName("0.80 → WARNING (between 70% and 85%)")
        void midpointWarning() {
            assertThat(WatermarkLevel.forUtilization(0.80)).isEqualTo(WatermarkLevel.WARNING);
        }

        @Test
        @DisplayName("0.8499 → WARNING (just below critical)")
        void justBelowCritical() {
            assertThat(WatermarkLevel.forUtilization(0.8499)).isEqualTo(WatermarkLevel.WARNING);
        }

        @Test
        @DisplayName("0.85 → CRITICAL (exact threshold)")
        void exactCriticalThreshold() {
            assertThat(WatermarkLevel.forUtilization(0.85)).isEqualTo(WatermarkLevel.CRITICAL);
        }

        @Test
        @DisplayName("0.90 → CRITICAL (between 85% and 95%)")
        void midpointCritical() {
            assertThat(WatermarkLevel.forUtilization(0.90)).isEqualTo(WatermarkLevel.CRITICAL);
        }

        @Test
        @DisplayName("0.9499 → CRITICAL (just below shedding)")
        void justBelowShedding() {
            assertThat(WatermarkLevel.forUtilization(0.9499)).isEqualTo(WatermarkLevel.CRITICAL);
        }

        @Test
        @DisplayName("0.95 → SHEDDING (exact threshold)")
        void exactSheddingThreshold() {
            assertThat(WatermarkLevel.forUtilization(0.95)).isEqualTo(WatermarkLevel.SHEDDING);
        }

        @Test
        @DisplayName("1.0 → SHEDDING (full utilization)")
        void fullUtilization() {
            assertThat(WatermarkLevel.forUtilization(1.0)).isEqualTo(WatermarkLevel.SHEDDING);
        }

        @Test
        @DisplayName("1.5 → SHEDDING (overflow scenario)")
        void overflowStillShedding() {
            assertThat(WatermarkLevel.forUtilization(1.5)).isEqualTo(WatermarkLevel.SHEDDING);
        }
    }

    @Nested
    @DisplayName("contains — range membership")
    class Contains {

        @Test
        @DisplayName("NORMAL.contains(0.5) == true")
        void normalContainsMid() {
            assertThat(WatermarkLevel.NORMAL.contains(0.5)).isTrue();
        }

        @Test
        @DisplayName("NORMAL.contains(0.70) == false (boundary belongs to WARNING)")
        void normalExcludesBoundary() {
            assertThat(WatermarkLevel.NORMAL.contains(0.70)).isFalse();
        }

        @Test
        @DisplayName("WARNING.contains(0.70) == true")
        void warningContainsLowerBound() {
            assertThat(WatermarkLevel.WARNING.contains(0.70)).isTrue();
        }
    }

    @Nested
    @DisplayName("isMoreSevereThan — ordinal ordering")
    class Severity {

        @Test
        @DisplayName("SHEDDING > CRITICAL > WARNING > NORMAL")
        void ordinalOrdering() {
            assertThat(WatermarkLevel.SHEDDING.isMoreSevereThan(WatermarkLevel.CRITICAL)).isTrue();
            assertThat(WatermarkLevel.CRITICAL.isMoreSevereThan(WatermarkLevel.WARNING)).isTrue();
            assertThat(WatermarkLevel.WARNING.isMoreSevereThan(WatermarkLevel.NORMAL)).isTrue();
            assertThat(WatermarkLevel.NORMAL.isMoreSevereThan(WatermarkLevel.NORMAL)).isFalse();
            assertThat(WatermarkLevel.NORMAL.isMoreSevereThan(WatermarkLevel.WARNING)).isFalse();
        }
    }
}
