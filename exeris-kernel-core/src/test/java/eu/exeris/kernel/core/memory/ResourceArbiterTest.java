/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.core.memory;

import eu.exeris.kernel.spi.memory.AllocationHint;
import eu.exeris.kernel.spi.memory.LeakDetectionMode;
import eu.exeris.kernel.spi.memory.LoanedBuffer;
import eu.exeris.kernel.spi.memory.MemoryAllocator;
import eu.exeris.kernel.spi.memory.MemoryStats;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.StructuredTaskScope;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * L1 Unit Tests: {@link ResourceArbiter} — decision caching, context-aware action mapping,
 * grace period, and concurrent correctness under Virtual Thread storm.
 *
 * @since 0.5.0
 */
@DisplayName("L1: ResourceArbiter")
class ResourceArbiterTest {

    // =========================================================================
    // Test infrastructure
    // =========================================================================

    private static MemoryAllocator stubAllocator(long allocated, long total) {
        return new MemoryAllocator() {
            @Override
            public MemoryStats stats() {
                long free = total < 0 ? 0L : total - allocated;
                return new MemoryStats(total, allocated, free,
                        0, 0, allocated, 0, 0, LeakDetectionMode.DISABLED);
            }

            @Override
            public LoanedBuffer allocate(AllocationHint h) {
                throw new UnsupportedOperationException();
            }

            @Override
            public LoanedBuffer allocateNetwork(int b) {
                throw new UnsupportedOperationException();
            }

            @Override
            public LoanedBuffer allocateCarrierSlab(int i) {
                throw new UnsupportedOperationException();
            }

            @Override
            public LoanedBuffer allocateInfrastructure(long s) {
                throw new UnsupportedOperationException();
            }

            @Override
            public void close() {
                // No-op: stub allocator holds no off-heap resources
            }
        };
    }

    /**
     * Helper: creates an arbiter whose 30-second grace period has definitively expired.
     */
    private static ResourceArbiter arbiterPastGrace(WatermarkLevel level) {
        WatermarkManager mgr = new WatermarkManager(stubAllocator(0, 1_000_000));
        mgr.forceLevel(level);
        long expiredNs = System.nanoTime() - TimeUnit.SECONDS.toNanos(31);
        return new ResourceArbiter(mgr, expiredNs);
    }

    // =========================================================================
    // Construction
    // =========================================================================

    @Nested
    @DisplayName("Construction validation")
    class Construction {

        @Test
        @DisplayName("null watermarkManager throws IllegalArgumentException")
        void nullThrows() {
            assertThatThrownBy(() -> new ResourceArbiter(null))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("isInGracePeriod() == true immediately after construction")
        void gracePeriodActiveAfterConstruction() {
            WatermarkManager mgr = new WatermarkManager(stubAllocator(0, 1_000_000));
            assertThat(new ResourceArbiter(mgr).isInGracePeriod()).isTrue();
        }

        @Test
        @DisplayName("isInGracePeriod() == false when startNs is 31s in the past")
        void gracePeriodExpiredWithPastStartNs() {
            WatermarkManager mgr = new WatermarkManager(stubAllocator(0, 1_000_000));
            long expired = System.nanoTime() - TimeUnit.SECONDS.toNanos(31);
            assertThat(new ResourceArbiter(mgr, expired).isInGracePeriod()).isFalse();
        }
    }

    // =========================================================================
    // Grace period
    // =========================================================================

    @Nested
    @DisplayName("Grace period — ALLOW overrides all pressure levels")
    class GracePeriod {

        @Test
        @DisplayName("decide() returns ALLOW for TRANSPORT_IO even at SHEDDING during grace")
        void graceOverridesSheddingTransport() {
            WatermarkManager mgr = new WatermarkManager(stubAllocator(0, 1_000_000));
            mgr.forceLevel(WatermarkLevel.SHEDDING);
            assertThat(new ResourceArbiter(mgr).decide(ResourceArbiter.Context.TRANSPORT_IO))
                    .isEqualTo(ResourceArbiter.Action.ALLOW);
        }

        @Test
        @DisplayName("decide() returns ALLOW for KERNEL_LOGIC even at SHEDDING during grace")
        void graceOverridesSheddingKernel() {
            WatermarkManager mgr = new WatermarkManager(stubAllocator(0, 1_000_000));
            mgr.forceLevel(WatermarkLevel.SHEDDING);
            assertThat(new ResourceArbiter(mgr).decide(ResourceArbiter.Context.KERNEL_LOGIC))
                    .isEqualTo(ResourceArbiter.Action.ALLOW);
        }
    }

    // =========================================================================
    // Action mapping — post-grace, all four levels x two contexts
    // =========================================================================

    @Nested
    @DisplayName("Action mapping — TRANSPORT_IO context (post-grace)")
    class TransportIoMapping {

        @Test
        @DisplayName("NORMAL → ALLOW")
        void normalAllows() {
            assertThat(arbiterPastGrace(WatermarkLevel.NORMAL)
                    .decide(ResourceArbiter.Context.TRANSPORT_IO))
                    .isEqualTo(ResourceArbiter.Action.ALLOW);
        }

        @Test
        @DisplayName("WARNING → THROTTLE")
        void warningThrottles() {
            assertThat(arbiterPastGrace(WatermarkLevel.WARNING)
                    .decide(ResourceArbiter.Context.TRANSPORT_IO))
                    .isEqualTo(ResourceArbiter.Action.THROTTLE);
        }

        @Test
        @DisplayName("CRITICAL → REJECT")
        void criticalRejects() {
            assertThat(arbiterPastGrace(WatermarkLevel.CRITICAL)
                    .decide(ResourceArbiter.Context.TRANSPORT_IO))
                    .isEqualTo(ResourceArbiter.Action.REJECT);
        }

        @Test
        @DisplayName("SHEDDING → SHED_LOAD")
        void sheddingSheds() {
            assertThat(arbiterPastGrace(WatermarkLevel.SHEDDING)
                    .decide(ResourceArbiter.Context.TRANSPORT_IO))
                    .isEqualTo(ResourceArbiter.Action.SHED_LOAD);
        }
    }

    @Nested
    @DisplayName("Action mapping — KERNEL_LOGIC context (post-grace, stricter threshold)")
    class KernelLogicMapping {

        @Test
        @DisplayName("NORMAL → ALLOW")
        void normalAllows() {
            assertThat(arbiterPastGrace(WatermarkLevel.NORMAL)
                    .decide(ResourceArbiter.Context.KERNEL_LOGIC))
                    .isEqualTo(ResourceArbiter.Action.ALLOW);
        }

        @Test
        @DisplayName("WARNING → THROTTLE")
        void warningThrottles() {
            assertThat(arbiterPastGrace(WatermarkLevel.WARNING)
                    .decide(ResourceArbiter.Context.KERNEL_LOGIC))
                    .isEqualTo(ResourceArbiter.Action.THROTTLE);
        }

        @Test
        @DisplayName("CRITICAL → SHED_LOAD (sheds background logic early to protect transport)")
        void criticalShedsLogic() {
            assertThat(arbiterPastGrace(WatermarkLevel.CRITICAL)
                    .decide(ResourceArbiter.Context.KERNEL_LOGIC))
                    .isEqualTo(ResourceArbiter.Action.SHED_LOAD);
        }

        @Test
        @DisplayName("SHEDDING → SHED_LOAD")
        void sheddingSheds() {
            assertThat(arbiterPastGrace(WatermarkLevel.SHEDDING)
                    .decide(ResourceArbiter.Context.KERNEL_LOGIC))
                    .isEqualTo(ResourceArbiter.Action.SHED_LOAD);
        }
    }

    // =========================================================================
    // Decision cache — 1 ms TTL
    // =========================================================================

    @Nested
    @DisplayName("Decision cache — 1 ms TTL (post-grace)")
    class DecisionCache {

        @Test
        @DisplayName("Rapid successive calls return the same cached action")
        void rapidCallsReturnCachedAction() {
            ResourceArbiter arbiter = arbiterPastGrace(WatermarkLevel.WARNING);
            ResourceArbiter.Action first = arbiter.decide(ResourceArbiter.Context.TRANSPORT_IO);
            ResourceArbiter.Action second = arbiter.decide(ResourceArbiter.Context.TRANSPORT_IO);
            assertThat(second).isEqualTo(first);
        }

        @Test
        @DisplayName("Cache reflects level change after TTL expires (> 1 ms)")
        void cacheExpiresAfterTtl() {
            WatermarkManager mgr = new WatermarkManager(stubAllocator(0, 1_000_000));
            long expired = System.nanoTime() - TimeUnit.SECONDS.toNanos(31);
            ResourceArbiter arbiter = new ResourceArbiter(mgr, expired);

            mgr.forceLevel(WatermarkLevel.NORMAL);
            assertThat(arbiter.decide(ResourceArbiter.Context.TRANSPORT_IO))
                    .isEqualTo(ResourceArbiter.Action.ALLOW);

            mgr.forceLevel(WatermarkLevel.SHEDDING);
            arbiter.transportDecisionNs = System.nanoTime() - 2_000_000L;

            assertThat(arbiter.decide(ResourceArbiter.Context.TRANSPORT_IO))
                    .isEqualTo(ResourceArbiter.Action.SHED_LOAD);
        }
    }

    // =========================================================================
    // Enum contracts
    // =========================================================================

    @Nested
    @DisplayName("Enum contracts")
    class EnumContracts {

        @Test
        @DisplayName("Action has exactly 4 values in escalation order")
        void actionFourValues() {
            assertThat(ResourceArbiter.Action.values()).containsExactly(
                    ResourceArbiter.Action.ALLOW,
                    ResourceArbiter.Action.THROTTLE,
                    ResourceArbiter.Action.REJECT,
                    ResourceArbiter.Action.SHED_LOAD);
        }

        @Test
        @DisplayName("Context has exactly 2 values")
        void contextTwoValues() {
            assertThat(ResourceArbiter.Context.values()).hasSize(2);
        }

        @Test
        @DisplayName("Context.contextName() returns stable string constant")
        void contextNames() {
            assertThat(ResourceArbiter.Context.TRANSPORT_IO.contextName()).isEqualTo("TRANSPORT_IO");
            assertThat(ResourceArbiter.Context.KERNEL_LOGIC.contextName()).isEqualTo("KERNEL_LOGIC");
        }
    }

    // =========================================================================
    // Concurrent correctness
    // =========================================================================

    @Nested
    @DisplayName("Concurrent correctness — Virtual Thread storm")
    class ConcurrentCorrectness {

        @Test
        @DisplayName("10 000 VTs calling decide() during grace — all return ALLOW, zero exceptions")
        @Timeout(30)
        void concurrentDecideDuringGrace() throws InterruptedException {
            WatermarkManager mgr = new WatermarkManager(stubAllocator(0, 1_000_000));
            ResourceArbiter arbiter = new ResourceArbiter(mgr);
            AtomicLong nonAllow = new AtomicLong(0);

            try (var scope = StructuredTaskScope.open(
                    StructuredTaskScope.Joiner.<Void>awaitAllSuccessfulOrThrow())) {
                for (int i = 0; i < 10_000; i++) {
                    scope.fork(() -> {
                        if (arbiter.decide(ResourceArbiter.Context.TRANSPORT_IO)
                                != ResourceArbiter.Action.ALLOW) {
                            nonAllow.incrementAndGet();
                        }
                        return null;
                    });
                }
                scope.join();
            }
            assertThat(nonAllow.get()).isZero();
        }

        @Test
        @DisplayName("10 000 VTs calling decide() post-grace at SHEDDING — all return SHED_LOAD")
        @Timeout(30)
        void concurrentDecidePostGraceShedding() throws InterruptedException {
            ResourceArbiter arbiter = arbiterPastGrace(WatermarkLevel.SHEDDING);
            AtomicLong nonShed = new AtomicLong(0);

            try (var scope = StructuredTaskScope.open(
                    StructuredTaskScope.Joiner.<Void>awaitAllSuccessfulOrThrow())) {
                for (int i = 0; i < 10_000; i++) {
                    scope.fork(() -> {
                        if (arbiter.decide(ResourceArbiter.Context.TRANSPORT_IO)
                                != ResourceArbiter.Action.SHED_LOAD) {
                            nonShed.incrementAndGet();
                        }
                        return null;
                    });
                }
                scope.join();
            }
            assertThat(nonShed.get()).isZero();
        }
    }

    // =========================================================================
    // ScalingContext override — per-tenant SLA thresholds
    // =========================================================================

    @Nested
    @DisplayName("decide(Context, ScalingContext) — per-tenant SLA override")
    class ScalingContextDecide {

        @Test
        @DisplayName("null context throws IllegalArgumentException")
        void nullContextThrows() {
            WatermarkManager mgr = new WatermarkManager(stubAllocator(0, 1_000_000));
            long past = System.nanoTime() - TimeUnit.SECONDS.toNanos(31);
            ResourceArbiter arb = new ResourceArbiter(mgr, past);
            assertThatThrownBy(() -> arb.decide(null, ScalingContext.standard()))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("null scalingContext throws IllegalArgumentException")
        void nullScalingContextThrows() {
            WatermarkManager mgr = new WatermarkManager(stubAllocator(0, 1_000_000));
            long past = System.nanoTime() - TimeUnit.SECONDS.toNanos(31);
            ResourceArbiter arb = new ResourceArbiter(mgr, past);
            assertThatThrownBy(() -> arb.decide(ResourceArbiter.Context.TRANSPORT_IO, null))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("during grace period returns ALLOW regardless of utilization")
        void gracePeriodReturnsAllow() {
            WatermarkManager mgr = new WatermarkManager(stubAllocator(900_000, 1_000_000));
            mgr.refresh();
            ResourceArbiter arb = new ResourceArbiter(mgr);
            assertThat(arb.decide(ResourceArbiter.Context.TRANSPORT_IO, ScalingContext.free()))
                    .isEqualTo(ResourceArbiter.Action.ALLOW);
        }

        @Test
        @DisplayName("free tier sheds at 86% utilization where premium still throttles")
        void freeShedsBeforePremium() {
            WatermarkManager mgr = new WatermarkManager(stubAllocator(860_000, 1_000_000));
            mgr.refresh();
            long past = System.nanoTime() - TimeUnit.SECONDS.toNanos(31);
            ResourceArbiter arb = new ResourceArbiter(mgr, past);

            assertThat(arb.decide(ResourceArbiter.Context.TRANSPORT_IO, ScalingContext.free()))
                    .isEqualTo(ResourceArbiter.Action.SHED_LOAD);
            assertThat(arb.decide(ResourceArbiter.Context.TRANSPORT_IO, ScalingContext.premium()))
                    .isEqualTo(ResourceArbiter.Action.THROTTLE);
        }

        @Test
        @DisplayName("standard tier at 72% utilization → THROTTLE")
        void standardAt72PctThrottles() {
            WatermarkManager mgr = new WatermarkManager(stubAllocator(720_000, 1_000_000));
            mgr.refresh();
            long past = System.nanoTime() - TimeUnit.SECONDS.toNanos(31);
            ResourceArbiter arb = new ResourceArbiter(mgr, past);

            assertThat(arb.decide(ResourceArbiter.Context.TRANSPORT_IO, ScalingContext.standard()))
                    .isEqualTo(ResourceArbiter.Action.THROTTLE);
        }

        @Test
        @DisplayName("standard tier at 0% utilization → ALLOW")
        void standardAt0PctAllows() {
            WatermarkManager mgr = new WatermarkManager(stubAllocator(0, 1_000_000));
            mgr.refresh();
            long past = System.nanoTime() - TimeUnit.SECONDS.toNanos(31);
            ResourceArbiter arb = new ResourceArbiter(mgr, past);

            assertThat(arb.decide(ResourceArbiter.Context.TRANSPORT_IO, ScalingContext.standard()))
                    .isEqualTo(ResourceArbiter.Action.ALLOW);
        }

        @Test
        @DisplayName("Community tier (total=0) → utilization 0% → ALLOW for all tiers")
        void communityNoFixedBudgetAlwaysAllow() {
            WatermarkManager mgr = new WatermarkManager(stubAllocator(500_000, -1));
            mgr.refresh();
            long past = System.nanoTime() - TimeUnit.SECONDS.toNanos(31);
            ResourceArbiter arb = new ResourceArbiter(mgr, past);

            assertThat(arb.decide(ResourceArbiter.Context.TRANSPORT_IO, ScalingContext.free()))
                    .isEqualTo(ResourceArbiter.Action.ALLOW);
        }
    }

    // =========================================================================
    // Utilization % in JFR event — never -1 after refresh
    // =========================================================================

    @Nested
    @DisplayName("utilizationPct — real value after WatermarkManager.refresh()")
    class UtilizationPct {

        @Test
        @DisplayName("WatermarkManager.currentUtilizationPct() returns 0 before first refresh")
        void utilizationPctZeroBeforeRefresh() {
            WatermarkManager mgr = new WatermarkManager(stubAllocator(500_000, 1_000_000));
            assertThat(mgr.currentUtilizationPct()).isZero();
        }

        @Test
        @DisplayName("WatermarkManager.currentUtilizationPct() returns correct % after refresh")
        void utilizationPctCorrectAfterRefresh() {
            WatermarkManager mgr = new WatermarkManager(stubAllocator(720_000, 1_000_000));
            mgr.refresh();
            assertThat(mgr.currentUtilizationPct()).isEqualTo(72);
        }

        @Test
        @DisplayName("WatermarkManager.currentUtilizationPct() returns 0 for Community (totalBytes=-1 sentinel)")
        void utilizationPctZeroForCommunity() {
            WatermarkManager mgr = new WatermarkManager(stubAllocator(999_999, -1));
            mgr.refresh();
            assertThat(mgr.currentUtilizationPct()).isZero();
        }

        @Test
        @DisplayName("WatermarkManager.currentUtilizationPct() clamps to 100 on over-allocation")
        void utilizationPctClampsAt100() {
            WatermarkManager mgr = new WatermarkManager(stubAllocator(1_000_000, 1_000_000));
            mgr.refresh();
            assertThat(mgr.currentUtilizationPct()).isEqualTo(100);
        }
    }
}
