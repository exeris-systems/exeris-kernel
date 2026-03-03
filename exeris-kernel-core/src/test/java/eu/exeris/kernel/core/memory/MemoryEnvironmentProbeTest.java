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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * L1 Unit Tests: {@link MemoryEnvironmentProbe}.
 *
 * @since 0.5.0
 */
@DisplayName("L1: MemoryEnvironmentProbe")
class MemoryEnvironmentProbeTest {

    @Nested
    @DisplayName("probe() — basic contract")
    class ProbeContract {

        @Test
        @DisplayName("probe() returns non-null MemoryBoundaries")
        void probeReturnsNonNull() {
            assertThat(MemoryEnvironmentProbe.probe()).isNotNull();
        }

        @Test
        @DisplayName("totalMemoryBytes > 0")
        void totalMemoryIsPositive() {
            assertThat(MemoryEnvironmentProbe.probe().totalMemoryBytes()).isPositive();
        }

        @Test
        @DisplayName("jvmHeapMaxBytes matches Runtime.getRuntime().maxMemory()")
        void jvmHeapMaxMatchesRuntime() {
            assertThat(MemoryEnvironmentProbe.probe().jvmHeapMaxBytes())
                    .isEqualTo(Runtime.getRuntime().maxMemory());
        }

        @Test
        @DisplayName("recommendedOffHeapBytes >= 0")
        void offHeapBudgetNonNegative() {
            assertThat(MemoryEnvironmentProbe.probe().recommendedOffHeapBytes())
                    .isGreaterThanOrEqualTo(0L);
        }

        @Test
        @DisplayName("headroomBytes == DEFAULT_HEADROOM_BYTES when using probe()")
        void defaultHeadroomUsed() {
            assertThat(MemoryEnvironmentProbe.probe().headroomBytes())
                    .isEqualTo(MemoryEnvironmentProbe.DEFAULT_HEADROOM_BYTES);
        }
    }

    @Nested
    @DisplayName("probe(headroomBytes) — custom headroom")
    class CustomHeadroom {

        @Test
        @DisplayName("probe(0) — offHeap == max(0, total - heap)")
        void zeroHeadroomCalculation() {
            MemoryEnvironmentProbe.MemoryBoundaries b = MemoryEnvironmentProbe.probe(0L);
            long expected = Math.max(0L, b.totalMemoryBytes() - b.jvmHeapMaxBytes());
            assertThat(b.recommendedOffHeapBytes()).isEqualTo(expected);
        }

        @Test
        @DisplayName("massive headroom clamps recommendedOffHeapBytes to 0, not negative")
        void massiveHeadroomClampsToZero() {
            assertThat(MemoryEnvironmentProbe.probe(Long.MAX_VALUE / 2).recommendedOffHeapBytes())
                    .isZero();
        }

        @Test
        @DisplayName("negative headroomBytes throws IllegalArgumentException")
        void negativeHeadroomThrows() {
            assertThatThrownBy(() -> MemoryEnvironmentProbe.probe(-1L))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @DisplayName("MemoryBoundaries record — validation")
    class MemoryBoundariesValidation {

        @Test
        @DisplayName("negative totalMemoryBytes throws IllegalArgumentException")
        void negativeTotalThrows() {
            assertThatThrownBy(() -> new MemoryEnvironmentProbe.MemoryBoundaries(-1, 0, 0, 0))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("negative recommendedOffHeapBytes throws IllegalArgumentException")
        void negativeOffHeapThrows() {
            assertThatThrownBy(() -> new MemoryEnvironmentProbe.MemoryBoundaries(1024, 512, -1, 0))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("valid construction — accessors return correct values")
        void validConstruction() {
            var b = new MemoryEnvironmentProbe.MemoryBoundaries(4096L, 2048L, 1536L, 512L);
            assertThat(b.totalMemoryBytes()).isEqualTo(4096L);
            assertThat(b.jvmHeapMaxBytes()).isEqualTo(2048L);
            assertThat(b.recommendedOffHeapBytes()).isEqualTo(1536L);
            assertThat(b.headroomBytes()).isEqualTo(512L);
        }
    }
}

