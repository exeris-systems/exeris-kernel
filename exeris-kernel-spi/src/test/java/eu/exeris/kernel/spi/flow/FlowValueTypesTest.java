/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.spi.flow;

import eu.exeris.kernel.spi.flow.model.FlowOutcome;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * L0 Contract: Flow domain value types — {@link FlowOutcome}, {@link FlowEngineStats},
 * and {@link FlowEngineCapabilities}.
 *
 * <h2>FlowOutcome ordinal stability</h2>
 * <p>The scheduler uses these ordinals in its dispatch switch table and in the
 * off-heap slab header flags. Silent reordering yields silent misbehaviour.
 *
 * <h2>FlowEngineStats — Valhalla-readiness</h2>
 * <p>All-primitive record; structural equals/hashCode; {@code ZERO} sentinel;
 * {@code empty()} factory returns same reference.
 *
 * <h2>FlowEngineCapabilities — constructor guard + withProvider()</h2>
 * <p>Template constants {@code COMMUNITY} / {@code ENTERPRISE} verified;
 * blank providerId rejected; {@code withProvider()} produces a new record
 * with all flags preserved and only providerId changed.
 *
 * @since 0.5.0
 */
@DisplayName("L0: Flow Value Types — FlowOutcome ordinals + FlowEngineStats + FlowEngineCapabilities")
class FlowValueTypesTest {

    // =========================================================================
    // FlowOutcome ordinal stability
    // =========================================================================

    @Nested
    @DisplayName("FlowOutcome ordinal stability — scheduler dispatch table regression shield")
    class FlowOutcomeOrdinals {

        @Test
        @DisplayName("CONTINUE ordinal == 0")
        void continueOrdinal() {
            assertThat(FlowOutcome.CONTINUE.ordinal()).isZero();
        }

        @Test
        @DisplayName("COMPLETE ordinal == 1")
        void completeOrdinal() {
            assertThat(FlowOutcome.COMPLETE.ordinal()).isEqualTo(1);
        }

        @Test
        @DisplayName("PARK ordinal == 2")
        void parkOrdinal() {
            assertThat(FlowOutcome.PARK.ordinal()).isEqualTo(2);
        }

        @Test
        @DisplayName("FAIL ordinal == 3")
        void failOrdinal() {
            assertThat(FlowOutcome.FAIL.ordinal()).isEqualTo(3);
        }

        @Test
        @DisplayName("Total FlowOutcome count == 4 — new outcome requires scheduler review")
        void totalCount() {
            assertThat(FlowOutcome.values()).hasSize(4);
        }

        @Test
        @DisplayName("valueOf() resolves all canonical names")
        void valueOfResolvesAll() {
            assertThat(FlowOutcome.valueOf("CONTINUE")).isEqualTo(FlowOutcome.CONTINUE);
            assertThat(FlowOutcome.valueOf("COMPLETE")).isEqualTo(FlowOutcome.COMPLETE);
            assertThat(FlowOutcome.valueOf("PARK")).isEqualTo(FlowOutcome.PARK);
            assertThat(FlowOutcome.valueOf("FAIL")).isEqualTo(FlowOutcome.FAIL);
        }
    }

    // =========================================================================
    // FlowEngineStats — Valhalla-readiness
    // =========================================================================

    @Nested
    @DisplayName("FlowEngineStats — Valhalla-readiness + ZERO sentinel")
    class FlowEngineStatsContract {

        @Test
        @DisplayName("ZERO has all fields at 0, except slabUtilizationPct == -1")
        void zeroSentinelValues() {
            FlowEngineStats z = FlowEngineStats.ZERO;
            assertThat(z.activeFlows()).isZero();
            assertThat(z.parkedFlows()).isZero();
            assertThat(z.completedFlows()).isZero();
            assertThat(z.failedFlows()).isZero();
            assertThat(z.compensationsRun()).isZero();
            assertThat(z.stepExecutions()).isZero();
            assertThat(z.schedulerQueueDepth()).isZero();
            assertThat(z.slabUtilizationPct()).isEqualTo(-1);
        }

        @Test
        @DisplayName("empty() returns the ZERO constant — same reference, no allocation")
        void emptyReturnsSameReference() {
            assertThat(FlowEngineStats.empty()).isSameAs(FlowEngineStats.ZERO);
        }

        @Test
        @DisplayName("Constructor stores all fields correctly")
        void constructorStoresFields() {
            FlowEngineStats s = new FlowEngineStats(10L, 3L, 50L, 2L, 1L, 100L, 5, 72);
            assertThat(s.activeFlows()).isEqualTo(10L);
            assertThat(s.parkedFlows()).isEqualTo(3L);
            assertThat(s.completedFlows()).isEqualTo(50L);
            assertThat(s.failedFlows()).isEqualTo(2L);
            assertThat(s.compensationsRun()).isEqualTo(1L);
            assertThat(s.stepExecutions()).isEqualTo(100L);
            assertThat(s.schedulerQueueDepth()).isEqualTo(5);
            assertThat(s.slabUtilizationPct()).isEqualTo(72);
        }

        @Test
        @DisplayName("Structural equals: two identical FlowEngineStats are equal")
        void structuralEquality() {
            FlowEngineStats a = new FlowEngineStats(5L, 1L, 10L, 0L, 0L, 20L, 2, 50);
            FlowEngineStats b = new FlowEngineStats(5L, 1L, 10L, 0L, 0L, 20L, 2, 50);
            assertThat(a).isEqualTo(b);
        }

        @Test
        @DisplayName("Structural hashCode: equal stats have same hashCode")
        void structuralHashCode() {
            FlowEngineStats a = new FlowEngineStats(5L, 1L, 10L, 0L, 0L, 20L, 2, 50);
            FlowEngineStats b = new FlowEngineStats(5L, 1L, 10L, 0L, 0L, 20L, 2, 50);
            assertThat(a).hasSameHashCodeAs(b);
        }

        @Test
        @DisplayName("Different slabUtilizationPct yields inequality")
        void differentSlabPctNotEqual() {
            FlowEngineStats a = new FlowEngineStats(0L, 0L, 0L, 0L, 0L, 0L, 0, 10);
            FlowEngineStats b = new FlowEngineStats(0L, 0L, 0L, 0L, 0L, 0L, 0, 20);
            assertThat(a).isNotEqualTo(b);
        }

        @Test
        @DisplayName("equals(null) returns false — no NPE")
        void equalsNullReturnsFalse() {
            assertThat(FlowEngineStats.ZERO.equals(null)).isFalse();
        }
    }

    // =========================================================================
    // FlowEngineCapabilities — constructor + template constants + withProvider()
    // =========================================================================

    @Nested
    @DisplayName("FlowEngineCapabilities — constructor guard + COMMUNITY/ENTERPRISE templates")
    class FlowEngineCapabilitiesContract {

        @Test
        @DisplayName("COMMUNITY template: deterministicExecution=false, offHeap=false, lockFree=false, zeroGc=false, persistence=true, compensation=true, choreography=true, providerId='community'")
        void communityTemplate() {
            FlowEngineCapabilities c = FlowEngineCapabilities.COMMUNITY;
            assertThat(c.deterministicExecution()).isFalse();
            assertThat(c.offHeapDescriptors()).isFalse();
            assertThat(c.lockFreeScheduler()).isFalse();
            assertThat(c.zeroGcAfterStart()).isFalse();
            assertThat(c.persistenceBacked()).isTrue();
            assertThat(c.compensationSupport()).isTrue();
            assertThat(c.choreographySupport()).isTrue();
            assertThat(c.providerId()).isEqualTo("community");
        }

        @Test
        @DisplayName("ENTERPRISE template: all performance flags=true, choreography=true, providerId='enterprise'")
        void enterpriseTemplate() {
            FlowEngineCapabilities e = FlowEngineCapabilities.ENTERPRISE;
            assertThat(e.deterministicExecution()).isTrue();
            assertThat(e.offHeapDescriptors()).isTrue();
            assertThat(e.lockFreeScheduler()).isTrue();
            assertThat(e.zeroGcAfterStart()).isTrue();
            assertThat(e.persistenceBacked()).isTrue();
            assertThat(e.compensationSupport()).isTrue();
            assertThat(e.choreographySupport()).isTrue();
            assertThat(e.providerId()).isEqualTo("enterprise");
        }

        @Test
        @DisplayName("Blank providerId throws IllegalArgumentException")
        void blankProviderIdThrows() {
            assertThatThrownBy(() ->
                    new FlowEngineCapabilities(false, false, false, false, false, false, false, ""))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("Null providerId throws IllegalArgumentException")
        void nullProviderIdThrows() {
            assertThatThrownBy(() ->
                    new FlowEngineCapabilities(false, false, false, false, false, false, false, null))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("withProvider() preserves all flags and changes only providerId")
        void withProviderPreservesFlags() {
            FlowEngineCapabilities branded = FlowEngineCapabilities.COMMUNITY.withProvider("my-flow");
            assertThat(branded.providerId()).isEqualTo("my-flow");
            assertThat(branded.deterministicExecution())
                    .isEqualTo(FlowEngineCapabilities.COMMUNITY.deterministicExecution());
            assertThat(branded.persistenceBacked())
                    .isEqualTo(FlowEngineCapabilities.COMMUNITY.persistenceBacked());
            assertThat(branded.compensationSupport())
                    .isEqualTo(FlowEngineCapabilities.COMMUNITY.compensationSupport());
            assertThat(branded.choreographySupport())
                    .isEqualTo(FlowEngineCapabilities.COMMUNITY.choreographySupport());
        }

        @Test
        @DisplayName("withProvider() returns a new record — does not mutate the original")
        void withProviderReturnsNewRecord() {
            FlowEngineCapabilities branded = FlowEngineCapabilities.ENTERPRISE.withProvider("my-enterprise");
            assertThat(branded).isNotSameAs(FlowEngineCapabilities.ENTERPRISE);
            assertThat(FlowEngineCapabilities.ENTERPRISE.providerId()).isEqualTo("enterprise");
        }

        @Test
        @DisplayName("Structural equals: two identical custom caps are equal")
        void structuralEquality() {
            FlowEngineCapabilities a = new FlowEngineCapabilities(true, false, true, false, true, true, true, "acme");
            FlowEngineCapabilities b = new FlowEngineCapabilities(true, false, true, false, true, true, true, "acme");
            assertThat(a).isEqualTo(b);
        }

        @Test
        @DisplayName("Structural hashCode: equal caps have same hashCode")
        void structuralHashCode() {
            FlowEngineCapabilities a = new FlowEngineCapabilities(true, false, true, false, true, true, true, "acme");
            FlowEngineCapabilities b = new FlowEngineCapabilities(true, false, true, false, true, true, true, "acme");
            assertThat(a).hasSameHashCodeAs(b);
        }
    }
}

