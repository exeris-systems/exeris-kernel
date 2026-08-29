/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.core.memory;

import eu.exeris.kernel.spi.memory.AllocationHint;
import eu.exeris.kernel.spi.memory.LeakDetectionMode;
import eu.exeris.kernel.spi.memory.LoanedBuffer;
import eu.exeris.kernel.spi.memory.MemoryAllocator;
import eu.exeris.kernel.spi.memory.MemoryStats;
import eu.exeris.kernel.tck.support.TckScope;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;


import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * L1 Unit Tests: {@link WatermarkManager} — pressure sampling and atomic level transitions.
 *
 * <p>Uses a hand-written stub {@link MemoryAllocator} to control {@link MemoryStats}
 * deterministically without any off-heap allocation.
 *
 * @since 0.5.0
 */
@DisplayName("L1: WatermarkManager")
class WatermarkManagerTest {

    // =========================================================================
    // Test double — controllable MemoryAllocator stub
    // =========================================================================

    /**
     * Minimal stub that returns configurable MemoryStats.
     */
    private static StubAllocator allocatorWith(long allocated, long total) {
        return new StubAllocator(allocated, total);
    }

    private static final class StubAllocator implements MemoryAllocator {
        private volatile long allocated;
        private volatile long total;

        StubAllocator(long allocated, long total) {
            this.allocated = allocated;
            this.total = total;
        }

        void setStats(long newAllocated, long newTotal) {
            this.allocated = newAllocated;
            this.total = newTotal;
        }

        @Override
        public MemoryStats stats() {
            long used = allocated;
            long cap = total;
            return new MemoryStats(cap, used, cap - used, 0, 0, used, 0, 0, LeakDetectionMode.DISABLED);
        }

        @Override
        public LoanedBuffer allocate(AllocationHint h) {
            throw new UnsupportedOperationException("stub");
        }

        @Override
        public LoanedBuffer allocateNetwork(int b) {
            throw new UnsupportedOperationException("stub");
        }

        @Override
        public LoanedBuffer allocateCarrierSlab(int i) {
            throw new UnsupportedOperationException("stub");
        }

        @Override
        public LoanedBuffer allocateInfrastructure(long s) {
            throw new UnsupportedOperationException("stub");
        }

        @Override
        public void close() { /* no-op */ }
    }

    // =========================================================================
    // Tests
    // =========================================================================

    @Nested
    @DisplayName("Construction validation")
    class ConstructionValidation {

        @Test
        @DisplayName("null allocator throws IllegalArgumentException")
        void nullAllocatorThrows() {
            assertThatThrownBy(() -> new WatermarkManager(null))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("initial currentLevel() is NORMAL")
        void initialLevelIsNormal() {
            var mgr = new WatermarkManager(allocatorWith(0, 1_000_000));
            assertThat(mgr.currentLevel()).isEqualTo(WatermarkLevel.NORMAL);
        }
    }

    @Nested
    @DisplayName("refresh() — level transitions")
    class RefreshTransitions {

        @Test
        @DisplayName("refresh at 0% utilization → NORMAL")
        void zeroUtilizationIsNormal() {
            var stub = allocatorWith(0, 1_000_000);
            var mgr = new WatermarkManager(stub);
            assertThat(mgr.refresh()).isEqualTo(WatermarkLevel.NORMAL);
        }

        @Test
        @DisplayName("refresh at 70% utilization → WARNING")
        void seventyPercentIsWarning() {
            var stub = allocatorWith(700_000, 1_000_000);
            var mgr = new WatermarkManager(stub);
            assertThat(mgr.refresh()).isEqualTo(WatermarkLevel.WARNING);
            assertThat(mgr.currentLevel()).isEqualTo(WatermarkLevel.WARNING);
        }

        @Test
        @DisplayName("refresh at 85% utilization → CRITICAL")
        void eightyFivePercentIsCritical() {
            var stub = allocatorWith(850_000, 1_000_000);
            var mgr = new WatermarkManager(stub);
            assertThat(mgr.refresh()).isEqualTo(WatermarkLevel.CRITICAL);
        }

        @Test
        @DisplayName("refresh at 95% utilization → SHEDDING")
        void ninetyFivePercentIsShedding() {
            var stub = allocatorWith(950_000, 1_000_000);
            var mgr = new WatermarkManager(stub);
            assertThat(mgr.refresh()).isEqualTo(WatermarkLevel.SHEDDING);
        }

        @Test
        @DisplayName("currentLevel() reflects last refresh() result")
        void currentLevelReflectsRefresh() {
            var stub = allocatorWith(0, 1_000_000);
            var mgr = new WatermarkManager(stub);

            mgr.refresh();
            assertThat(mgr.currentLevel()).isEqualTo(WatermarkLevel.NORMAL);

            stub.setStats(800_000, 1_000_000);
            mgr.refresh();
            assertThat(mgr.currentLevel()).isEqualTo(WatermarkLevel.WARNING);

            stub.setStats(960_000, 1_000_000);
            mgr.refresh();
            assertThat(mgr.currentLevel()).isEqualTo(WatermarkLevel.SHEDDING);
        }

        @Test
        @DisplayName("allocator with totalBytes <= 0 → always NORMAL (unknown budget)")
        void unknownBudgetIsAlwaysNormal() {
            var stub = allocatorWith(0, 0);
            var mgr = new WatermarkManager(stub);
            assertThat(mgr.refresh()).isEqualTo(WatermarkLevel.NORMAL);
        }
    }

    @Nested
    @DisplayName("forceLevel() — test helper")
    class ForceLevel {

        @Test
        @DisplayName("forceLevel(SHEDDING) → currentLevel() == SHEDDING")
        void forceLevelUpdatesCurrent() {
            var mgr = new WatermarkManager(allocatorWith(0, 1_000_000));
            mgr.forceLevel(WatermarkLevel.SHEDDING);
            assertThat(mgr.currentLevel()).isEqualTo(WatermarkLevel.SHEDDING);
        }

        @Test
        @DisplayName("forceLevel(NORMAL) after SHEDDING → currentLevel() reverts")
        void forceLevelReverts() {
            var mgr = new WatermarkManager(allocatorWith(0, 1_000_000));
            mgr.forceLevel(WatermarkLevel.SHEDDING);
            mgr.forceLevel(WatermarkLevel.NORMAL);
            assertThat(mgr.currentLevel()).isEqualTo(WatermarkLevel.NORMAL);
        }
    }

    @Nested
    @DisplayName("Thread-safety — concurrent refresh under Virtual Threads")
    class ConcurrentRefresh {

        @Test
        @DisplayName("100 concurrent refresh() calls converge to the correct level")
        @Timeout(10)
        void concurrentRefreshConverges() throws InterruptedException {
            var stub = allocatorWith(850_000, 1_000_000);
            var mgr = new WatermarkManager(stub);

            try (var scope = TckScope.openFailFast()) {
                for (int i = 0; i < 100; i++) {
                    scope.fork(mgr::refresh);
                }
                scope.join();
            }

            assertThat(mgr.currentLevel()).isEqualTo(WatermarkLevel.CRITICAL);
        }
    }
}
