/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.spi.memory;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

/**
 * L0 Contract: {@link MemoryStats} — constructor validation, utilization(), zero()
 * null-object, and Valhalla-readiness (structural equality, no identity operations).
 *
 * <h2>Valhalla-Readiness Proof</h2>
 * <p>These tests guarantee that {@code MemoryStats} satisfies the three prerequisites
 * for migration to a {@code value record} (JEP 401):
 * <ol>
 *   <li>Structural {@code equals()}/{@code hashCode()} — no identity-based equality.</li>
 *   <li>No {@code synchronized} usage — records cannot be monitor-locked as value types.</li>
 *   <li>No {@code System.identityHashCode()} — must not be relied upon.</li>
 * </ol>
 *
 * @since 0.5.0
 */
@DisplayName("L0: MemoryStats — Valhalla-Readiness + Constructor Contract")
class MemoryStatsTest {

    // -----------------------------------------------------------------------
    // 1. Constructor validation
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("1. Constructor validation — invariants enforced at construction")
    class ConstructorValidation {

        @Test
        @DisplayName("Valid stats construct without exception")
        void validStatsConstruct() {
            MemoryStats s = new MemoryStats(
                    1024L, 512L, 512L, 10L, 8L, 512L, 2, 0L, LeakDetectionMode.DISABLED);
            assertThat(s.totalBytes()).isEqualTo(1024L);
            assertThat(s.allocatedBytes()).isEqualTo(512L);
            assertThat(s.releaseCount()).isEqualTo(8L);
            assertThat(s.carrierPoolCount()).isEqualTo(2);
        }

        @Test
        @DisplayName("Negative allocatedBytes throws IllegalArgumentException")
        void negativeAllocatedBytesThrows() {
            assertThatThrownBy(() ->
                    new MemoryStats(1024L, -1L, 512L, 0L, 0L, 0L, 0, 0L, LeakDetectionMode.DISABLED))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("Negative freeBytes throws IllegalArgumentException")
        void negativeFreeBytesThrows() {
            assertThatThrownBy(() ->
                    new MemoryStats(1024L, 0L, -1L, 0L, 0L, 0L, 0, 0L, LeakDetectionMode.DISABLED))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("Negative peakAllocatedBytes throws IllegalArgumentException")
        void negativePeakThrows() {
            assertThatThrownBy(() ->
                    new MemoryStats(1024L, 0L, 0L, 0L, 0L, -1L, 0, 0L, LeakDetectionMode.DISABLED))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("Negative carrierPoolCount throws IllegalArgumentException")
        void negativeCarrierPoolCountThrows() {
            assertThatThrownBy(() ->
                    new MemoryStats(1024L, 0L, 0L, 0L, 0L, 0L, -1, 0L, LeakDetectionMode.DISABLED))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("Negative leakCount throws IllegalArgumentException")
        void negativeLeakCountThrows() {
            assertThatThrownBy(() ->
                    new MemoryStats(1024L, 0L, 0L, 0L, 0L, 0L, 0, -1L, LeakDetectionMode.DISABLED))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("null leakDetectionMode throws NullPointerException")
        void nullLeakDetectionModeThrows() {
            assertThatThrownBy(() ->
                    new MemoryStats(1024L, 0L, 0L, 0L, 0L, 0L, 0, 0L, null))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("All-zero is valid — null-object pattern for heap-only allocators")
        void allZeroIsValid() {
            MemoryStats s = new MemoryStats(0L, 0L, 0L, 0L, 0L, 0L, 0, 0L, LeakDetectionMode.DISABLED);
            assertThat(s.totalBytes()).isZero();
        }

        @Test
        @DisplayName("totalBytes=-1 is valid — sentinel for heap-only allocators without budget")
        void minusTotalBytesIsValidSentinel() {
            MemoryStats s = new MemoryStats(-1L, 0L, 0L, 0L, 0L, 0L, 0, 0L, LeakDetectionMode.DISABLED);
            assertThat(s.totalBytes()).isEqualTo(-1L);
        }
    }

    // -----------------------------------------------------------------------
    // 2. utilization() semantics
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("2. utilization() — ratio contract [0.0, 1.0]")
    class UtilizationTests {

        @Test
        @DisplayName("utilization() = allocatedBytes / totalBytes")
        void utilizationIsRatio() {
            MemoryStats s = new MemoryStats(
                    1000L, 250L, 750L, 0L, 0L, 250L, 0, 0L, LeakDetectionMode.DISABLED);
            assertThat(s.utilization()).isCloseTo(0.25, within(1e-9));
        }

        @Test
        @DisplayName("utilization() = 1.0 when fully allocated")
        void utilizationAtCapacity() {
            MemoryStats s = new MemoryStats(
                    1000L, 1000L, 0L, 0L, 0L, 1000L, 0, 0L, LeakDetectionMode.DISABLED);
            assertThat(s.utilization()).isCloseTo(1.0, within(1e-9));
        }

        @Test
        @DisplayName("utilization() = 0.0 when totalBytes == 0 — no division by zero")
        void utilizationZeroWhenNoBudget() {
            MemoryStats s = new MemoryStats(
                    0L, 0L, 0L, 5L, 5L, 0L, 0, 0L, LeakDetectionMode.DISABLED);
            assertThat(s.utilization()).isZero();
        }
    }

    // -----------------------------------------------------------------------
    // 3. zero() null-object
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("3. zero() null-object — prevents null checks in diagnostic code")
    class ZeroNullObject {

        @Test
        @DisplayName("MemoryStats.zero() returns all-zero fields")
        void zeroReturnsAllZero() {
            MemoryStats z = MemoryStats.zero();
            assertThat(z.totalBytes()).isZero();
            assertThat(z.allocatedBytes()).isZero();
            assertThat(z.freeBytes()).isZero();
            assertThat(z.allocationCount()).isZero();
            assertThat(z.releaseCount()).isZero();
            assertThat(z.peakAllocatedBytes()).isZero();
            assertThat(z.carrierPoolCount()).isZero();
            assertThat(z.leakCount()).isZero();
        }

        @Test
        @DisplayName("MemoryStats.zero().leakDetectionMode == DISABLED")
        void zeroLeakModeIsDisabled() {
            assertThat(MemoryStats.zero().leakDetectionMode()).isEqualTo(LeakDetectionMode.DISABLED);
        }

        @Test
        @DisplayName("utilization() on zero() == 0.0 — safe division guard")
        void utilizationOnZeroIsSafe() {
            assertThat(MemoryStats.zero().utilization()).isZero();
        }
    }

    // -----------------------------------------------------------------------
    // 4. Valhalla-readiness: structural equality, no identity ops
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("4. Valhalla-readiness — structural equals/hashCode, no identity ops")
    class ValhallaReadiness {

        /**
         * The {@code value} modifier is the only difference between this carrier on the two
         * distribution lines, and nothing else in the suite would notice if it were lost to a merge
         * or a reformat — the structural equality cases below pass for an identity record too. This
         * asserts the modifier itself, so the bytecode check that first proved it is repeatable
         * rather than a one-time inspection.
         */
        @Test
        @DisplayName("MemoryStats is a value class on the preview line (JEP 401)")
        void isValueClass() {
            assertThat(MemoryStats.class.isValue())
                    .as("MemoryStats must carry the value modifier; ACC_IDENTITY must be clear")
                    .isTrue();
        }

        @Test
        @DisplayName("Two structurally equal MemoryStats are equals()")
        void equalityByStructure() {
            MemoryStats a = new MemoryStats(
                    100L, 50L, 50L, 1L, 1L, 50L, 0, 0L, LeakDetectionMode.DISABLED);
            MemoryStats b = new MemoryStats(
                    100L, 50L, 50L, 1L, 1L, 50L, 0, 0L, LeakDetectionMode.DISABLED);
            assertThat(a).isEqualTo(b);
        }

        @Test
        @DisplayName("Structurally equal records have identical hashCode()")
        void equalHashCode() {
            MemoryStats a = new MemoryStats(
                    100L, 50L, 50L, 1L, 1L, 50L, 0, 0L, LeakDetectionMode.DISABLED);
            MemoryStats b = new MemoryStats(
                    100L, 50L, 50L, 1L, 1L, 50L, 0, 0L, LeakDetectionMode.DISABLED);
            assertThat(a).hasSameHashCodeAs(b);
        }

        @Test
        @DisplayName("Different stats are not equal")
        void differentStatsNotEqual() {
            MemoryStats a = new MemoryStats(
                    100L, 50L, 50L, 1L, 1L, 50L, 0, 0L, LeakDetectionMode.DISABLED);
            MemoryStats b = new MemoryStats(
                    100L, 60L, 40L, 2L, 1L, 60L, 0, 0L, LeakDetectionMode.DISABLED);
            assertThat(a).isNotEqualTo(b);
        }

        @Test
        @DisplayName("equals() is reflexive — a.equals(a)")
        void equalsReflexive() {
            MemoryStats a = new MemoryStats(
                    100L, 50L, 50L, 0L, 0L, 50L, 0, 0L, LeakDetectionMode.DISABLED);
            assertThat(a).isEqualTo(a);
        }

        @Test
        @DisplayName("equals() is symmetric — a.equals(b) && b.equals(a)")
        void equalsSymmetric() {
            MemoryStats a = new MemoryStats(
                    100L, 50L, 50L, 0L, 0L, 50L, 0, 0L, LeakDetectionMode.DISABLED);
            MemoryStats b = new MemoryStats(
                    100L, 50L, 50L, 0L, 0L, 50L, 0, 0L, LeakDetectionMode.DISABLED);
            assertThat(a.equals(b)).isTrue();
            assertThat(b.equals(a)).isTrue();
        }

        @Test
        @DisplayName("equals(null) returns false — no NullPointerException")
        void equalsNullReturnsFalse() {
            MemoryStats a = MemoryStats.zero();
            assertThat(a.equals(null)).isFalse();
        }

        @Test
        @DisplayName("LeakDetectionMode differs → not equal (all fields participate in equals)")
        void differentLeakModeNotEqual() {
            MemoryStats a = new MemoryStats(
                    100L, 50L, 50L, 0L, 0L, 50L, 0, 0L, LeakDetectionMode.DISABLED);
            MemoryStats b = new MemoryStats(
                    100L, 50L, 50L, 0L, 0L, 50L, 0, 0L, LeakDetectionMode.SAMPLED);
            assertThat(a).isNotEqualTo(b);
        }
    }
}
