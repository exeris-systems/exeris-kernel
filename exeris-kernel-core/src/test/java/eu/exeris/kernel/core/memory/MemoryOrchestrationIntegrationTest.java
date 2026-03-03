/*
 * Copyright (C) 2025-2026 Exeris. All rights reserved.
 *
 * This code is part of the Exeris Systems.
 * Distributed under the proprietary Exeris Software License.
 * Unauthorized copying or distribution is prohibited.
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

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * L2 Integration Tests: Memory Orchestration Pipeline.
 *
 * <h2>System Under Test</h2>
 * <p>Validates the end-to-end interaction between:
 * <ul>
 *   <li>{@link WatermarkManager} — pressure sampling from {@link MemoryAllocator#stats()}</li>
 *   <li>{@link ResourceArbiter} — load-shedding decisions based on watermark levels</li>
 *   <li>{@link MemoryMaintenanceTask} — periodic refresh loop driving both components</li>
 * </ul>
 *
 * <h2>Architectural Invariant ("The Governor Contract")</h2>
 * <p>When {@link MemoryAllocator#stats()} reports utilization above the SHEDDING threshold
 * (>= 95%), the {@link ResourceArbiter} MUST return {@link ResourceArbiter.Action#SHED_LOAD}
 * for both contexts — within one watermark refresh cycle.
 *
 * <h2>Why L2 and not TCK</h2>
 * <p>{@code ResourceArbiter} and {@code WatermarkManager} are Core-tier classes, not SPI
 * contracts. The TCK ({@code exeris-kernel-tck}) is restricted to SPI imports only. These
 * integration tests therefore live in {@code exeris-kernel-core} and test the internal
 * orchestration contract, not the SPI signal.
 *
 * @see WatermarkManager
 * @see ResourceArbiter
 * @see MemoryMaintenanceTask
 * @since 0.5.0
 */
@DisplayName("L2: Memory Orchestration Pipeline Integration")
class MemoryOrchestrationIntegrationTest {

    // =========================================================================
    // Test infrastructure
    // =========================================================================

    /**
     * Controllable stub allocator — allows simulating any utilization level.
     */
    private static final class ControllableAllocator implements MemoryAllocator {

        private volatile long allocatedBytes;
        private volatile long totalBytes;
        private final AtomicInteger maintenanceCalls = new AtomicInteger();

        ControllableAllocator(long totalBytes) {
            this.totalBytes = totalBytes;
            this.allocatedBytes = 0L;
        }

        void setUtilization(double ratio) {
            this.allocatedBytes = (long) (totalBytes * ratio);
        }

        int maintenanceCalls() {
            return maintenanceCalls.get();
        }

        @Override
        public MemoryStats stats() {
            long alloc = allocatedBytes;
            long total = totalBytes;
            return new MemoryStats(
                    total, alloc, total - alloc,
                    0, 0, alloc, 0, 0,
                    LeakDetectionMode.DISABLED);
        }

        @Override
        public void performMaintenance() {
            maintenanceCalls.incrementAndGet();
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
        public void close() { /* no-op */ }
    }

    // =========================================================================
    // Governor Contract — WatermarkManager → ResourceArbiter propagation
    // =========================================================================

    @Nested
    @DisplayName("Governor Contract — pressure signal propagation")
    class GovernorContract {

        @Test
        @DisplayName("Normal utilization (0%) → ALLOW for both contexts")
        void normalUtilizationAllows() {
            ControllableAllocator alloc = new ControllableAllocator(1_000_000L);
            WatermarkManager mgr = new WatermarkManager(alloc);
            long past = System.nanoTime() - TimeUnit.SECONDS.toNanos(31);
            ResourceArbiter arb = new ResourceArbiter(mgr, past);

            alloc.setUtilization(0.0);
            mgr.refresh();

            assertThat(arb.decide(ResourceArbiter.Context.TRANSPORT_IO))
                    .as("NORMAL utilization must yield ALLOW for TRANSPORT_IO")
                    .isEqualTo(ResourceArbiter.Action.ALLOW);
            assertThat(arb.decide(ResourceArbiter.Context.KERNEL_LOGIC))
                    .as("NORMAL utilization must yield ALLOW for KERNEL_LOGIC")
                    .isEqualTo(ResourceArbiter.Action.ALLOW);
        }

        @Test
        @DisplayName("Warning utilization (72%) → THROTTLE for TRANSPORT_IO")
        void warningUtilizationThrottles() {
            ControllableAllocator alloc = new ControllableAllocator(1_000_000L);
            WatermarkManager mgr = new WatermarkManager(alloc);
            long past = System.nanoTime() - TimeUnit.SECONDS.toNanos(31);
            ResourceArbiter arb = new ResourceArbiter(mgr, past);

            alloc.setUtilization(0.72);
            mgr.refresh();

            forceCacheExpiry(arb);

            assertThat(arb.decide(ResourceArbiter.Context.TRANSPORT_IO))
                    .as("72%% utilization must yield THROTTLE")
                    .isEqualTo(ResourceArbiter.Action.THROTTLE);
        }

        @Test
        @DisplayName("Critical utilization (88%) → REJECT for TRANSPORT_IO, SHED_LOAD for KERNEL_LOGIC (each fresh cache miss)")
        void criticalUtilizationBifurcates() {
            ControllableAllocator alloc = new ControllableAllocator(1_000_000L);
            WatermarkManager mgr = new WatermarkManager(alloc);
            long past = System.nanoTime() - TimeUnit.SECONDS.toNanos(31);
            ResourceArbiter arb = new ResourceArbiter(mgr, past);

            alloc.setUtilization(0.88);
            mgr.refresh();

            forceCacheExpiry(arb);
            assertThat(arb.decide(ResourceArbiter.Context.TRANSPORT_IO))
                    .as("CRITICAL must REJECT transport to preserve bandwidth")
                    .isEqualTo(ResourceArbiter.Action.REJECT);

            forceCacheExpiry(arb);
            assertThat(arb.decide(ResourceArbiter.Context.KERNEL_LOGIC))
                    .as("CRITICAL must SHED_LOAD kernel logic to protect transport")
                    .isEqualTo(ResourceArbiter.Action.SHED_LOAD);
        }

        @Test
        @DisplayName("Shedding utilization (97%) → SHED_LOAD for all contexts")
        void sheddingUtilizationShedsAll() {
            ControllableAllocator alloc = new ControllableAllocator(1_000_000L);
            WatermarkManager mgr = new WatermarkManager(alloc);
            long past = System.nanoTime() - TimeUnit.SECONDS.toNanos(31);
            ResourceArbiter arb = new ResourceArbiter(mgr, past);

            alloc.setUtilization(0.97);
            mgr.refresh();
            forceCacheExpiry(arb);

            assertThat(arb.decide(ResourceArbiter.Context.TRANSPORT_IO))
                    .as("SHEDDING must SHED_LOAD transport")
                    .isEqualTo(ResourceArbiter.Action.SHED_LOAD);
            assertThat(arb.decide(ResourceArbiter.Context.KERNEL_LOGIC))
                    .as("SHEDDING must SHED_LOAD kernel logic")
                    .isEqualTo(ResourceArbiter.Action.SHED_LOAD);
        }

        @Test
        @DisplayName("Pressure recovery: SHEDDING → NORMAL → arbiter reverts to ALLOW after TTL")
        void pressureRecovery() {
            ControllableAllocator alloc = new ControllableAllocator(1_000_000L);
            WatermarkManager mgr = new WatermarkManager(alloc);
            long past = System.nanoTime() - TimeUnit.SECONDS.toNanos(31);
            ResourceArbiter arb = new ResourceArbiter(mgr, past);

            alloc.setUtilization(0.97);
            mgr.refresh();
            forceCacheExpiry(arb);
            assertThat(arb.decide(ResourceArbiter.Context.TRANSPORT_IO))
                    .isEqualTo(ResourceArbiter.Action.SHED_LOAD);

            alloc.setUtilization(0.10);
            mgr.refresh();

            forceCacheExpiry(arb);
            assertThat(arb.decide(ResourceArbiter.Context.TRANSPORT_IO))
                    .as("After pressure recovery, arbiter must revert to ALLOW")
                    .isEqualTo(ResourceArbiter.Action.ALLOW);
        }
    }

    // =========================================================================
    // MemoryMaintenanceTask integration
    // =========================================================================

    @Nested
    @DisplayName("MemoryMaintenanceTask drives the pipeline")
    class MaintenanceTaskDrivesPipeline {

        @Test
        @DisplayName("Task drives watermark refresh AND performMaintenance() within interval")
        @Timeout(10)
        void taskDrivesBothRefreshAndMaintenance() {
            ControllableAllocator alloc = new ControllableAllocator(1_000_000L);
            WatermarkManager mgr = new WatermarkManager(alloc);

            alloc.setUtilization(0.97);

            try (MemoryMaintenanceTask task = new MemoryMaintenanceTask(alloc, mgr, 50L, 25L)) {
                task.start();
                awaitCondition(() -> alloc.maintenanceCalls() >= 1, 5_000);
            }

            assertThat(mgr.currentLevel())
                    .as("WatermarkManager must reflect SHEDDING after maintenance loop ran")
                    .isEqualTo(WatermarkLevel.SHEDDING);

            assertThat(alloc.maintenanceCalls())
                    .as("performMaintenance() must have been called at least once")
                    .isGreaterThanOrEqualTo(1);
        }

        @Test
        @DisplayName("Pipeline reacts to dynamic pressure change driven by the task")
        @Timeout(15)
        void pipelineReactsToDynamicPressure() {
            ControllableAllocator alloc = new ControllableAllocator(1_000_000L);
            WatermarkManager mgr = new WatermarkManager(alloc);
            long past = System.nanoTime() - TimeUnit.SECONDS.toNanos(31);
            ResourceArbiter arb = new ResourceArbiter(mgr, past);

            AtomicReference<ResourceArbiter.Action> capturedAction =
                    new AtomicReference<>(ResourceArbiter.Action.ALLOW);

            alloc.setUtilization(0.0);

            try (MemoryMaintenanceTask task = new MemoryMaintenanceTask(alloc, mgr, 40L, 20L)) {
                task.start();
                awaitCondition(() -> alloc.maintenanceCalls() >= 1, 3_000);

                alloc.setUtilization(0.97);
                int callsBefore = alloc.maintenanceCalls();
                awaitCondition(() -> alloc.maintenanceCalls() > callsBefore, 5_000);

                forceCacheExpiry(arb);
                capturedAction.set(arb.decide(ResourceArbiter.Context.TRANSPORT_IO));
            }

            assertThat(capturedAction.get())
                    .as("After pressure spike and refresh cycle, arbiter must SHED_LOAD")
                    .isEqualTo(ResourceArbiter.Action.SHED_LOAD);
        }
    }

    // =========================================================================
    // ScalingContext integration with ResourceArbiter signal
    // =========================================================================

    @Nested
    @DisplayName("ScalingContext tier differentiation")
    class ScalingContextTierDifferentiation {

        @Test
        @DisplayName("Free tier sheds at 86% while premium tier only throttles")
        void freeShedsBeforePremium() {
            ControllableAllocator alloc = new ControllableAllocator(1_000_000L);
            alloc.setUtilization(0.86);

            assertThat(ScalingContext.free().actionFor(0.86))
                    .as("Free tier must shed at 86%% utilization")
                    .isEqualTo(ResourceArbiter.Action.SHED_LOAD);

            assertThat(ScalingContext.premium().actionFor(0.86))
                    .as("Premium tier must only throttle at 86%% utilization")
                    .isEqualTo(ResourceArbiter.Action.THROTTLE);

            assertThat(ScalingContext.standard().actionFor(0.86))
                    .as("Standard tier must reject at 86%% utilization")
                    .isEqualTo(ResourceArbiter.Action.REJECT);
        }

        @Test
        @DisplayName("All tiers shed at 100% utilization")
        void allTiersShedAtFullCapacity() {
            assertThat(ScalingContext.free().actionFor(1.0))
                    .isEqualTo(ResourceArbiter.Action.SHED_LOAD);
            assertThat(ScalingContext.standard().actionFor(1.0))
                    .isEqualTo(ResourceArbiter.Action.SHED_LOAD);
            assertThat(ScalingContext.premium().actionFor(1.0))
                    .isEqualTo(ResourceArbiter.Action.SHED_LOAD);
        }

        @Test
        @DisplayName("Queue saturation: free tier saturates at 2000, standard at 5000")
        void queueSaturationThresholds() {
            assertThat(ScalingContext.free().isQueueSaturated(2_001)).isTrue();
            assertThat(ScalingContext.free().isQueueSaturated(2_000)).isFalse();
            assertThat(ScalingContext.standard().isQueueSaturated(5_001)).isTrue();
            assertThat(ScalingContext.standard().isQueueSaturated(5_000)).isFalse();
            assertThat(ScalingContext.premium().isQueueSaturated(8_001)).isTrue();
            assertThat(ScalingContext.premium().isQueueSaturated(8_000)).isFalse();
        }
    }

    // =========================================================================
    // Helper
    // =========================================================================

    /**
     * Advances the arbiter's internal cache timestamp far into the past, ensuring
     * the next {@code decide()} call performs a full re-evaluation from the watermark.
     *
     * <p>Uses the package-private {@code cachedDecisionNs} and {@code cachedActionOrdinal}
     * fields (same package — legal in tests collocated with the SUT).
     */
    private static void forceCacheExpiry(ResourceArbiter arbiter) {
        long expiredNs = System.nanoTime() - 2_000_000L;
        arbiter.transportDecisionNs = expiredNs;
        arbiter.logicDecisionNs = expiredNs;
    }

    /**
     * Waits for a condition to become true, polling repeatedly until the condition is met
     * or the timeout is reached.
     *
     * @param condition the condition to wait for
     * @param timeout   the maximum time to wait, in milliseconds
     * @return true if the condition was met, false if the timeout was reached
     */
    private static boolean awaitCondition(BooleanSupplier condition, long timeout) {
        long startTime = System.currentTimeMillis();
        while (System.currentTimeMillis() - startTime < timeout) {
            if (condition.getAsBoolean()) {
                return true;
            }
            LockSupport.parkNanos(100_000);
        }
        return false;
    }
}
