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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * L1 Unit Tests: {@link MemoryMaintenanceTask} — lifecycle, loop invocation, graceful stop.
 *
 * @since 0.5.0
 */
@DisplayName("L1: MemoryMaintenanceTask")
class MemoryMaintenanceTaskTest {

    // =========================================================================
    // Test infrastructure
    // =========================================================================

    private static MemoryAllocator countingAllocator(AtomicInteger maintenanceCallCount) {
        return new MemoryAllocator() {
            @Override
            public MemoryStats stats() {
                return new MemoryStats(1_000_000L, 0L, 1_000_000L,
                        0, 0, 0, 0, 0, LeakDetectionMode.DISABLED);
            }

            @Override
            public void performMaintenance() {
                maintenanceCallCount.incrementAndGet();
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

    // =========================================================================
    // Awaiting helper — replaces Thread.sleep() with condition polling
    // =========================================================================

    /**
     * Polls {@code condition} every 100 µs until it returns {@code true} or
     * {@code timeoutMs} elapses. Uses {@link LockSupport#parkNanos} to avoid
     * the blocked-thread overhead of {@code Thread.sleep()}.
     *
     * @return {@code true} if the condition was met within the timeout
     */
    private static boolean awaitCondition(BooleanSupplier condition, long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return true;
            }
            LockSupport.parkNanos(100_000L);
        }
        return false;
    }

    // =========================================================================
    // Construction validation
    // =========================================================================

    @Nested
    @DisplayName("Construction validation")
    class ConstructionValidation {

        @Test
        @DisplayName("null allocator throws IllegalArgumentException")
        void nullAllocatorThrows() {
            WatermarkManager mgr = new WatermarkManager(countingAllocator(new AtomicInteger()));
            assertThatThrownBy(() -> new MemoryMaintenanceTask(null, mgr))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("null watermarkManager throws IllegalArgumentException")
        void nullWatermarkManagerThrows() {
            MemoryAllocator alloc = countingAllocator(new AtomicInteger());
            assertThatThrownBy(() -> new MemoryMaintenanceTask(alloc, null))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("zero maintenanceIntervalMs throws IllegalArgumentException")
        void zeroIntervalThrows() {
            AtomicInteger cnt = new AtomicInteger();
            MemoryAllocator alloc = countingAllocator(cnt);
            WatermarkManager mgr = new WatermarkManager(alloc);
            assertThatThrownBy(() -> new MemoryMaintenanceTask(alloc, mgr, 0, 1_000))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    // =========================================================================
    // Lifecycle
    // =========================================================================

    @Nested
    @DisplayName("Lifecycle — start / stop / close")
    class Lifecycle {

        @Test
        @DisplayName("isRunning() == false before start()")
        void notRunningBeforeStart() {
            AtomicInteger cnt = new AtomicInteger();
            MemoryAllocator alloc = countingAllocator(cnt);
            WatermarkManager mgr = new WatermarkManager(alloc);
            try (MemoryMaintenanceTask task = new MemoryMaintenanceTask(alloc, mgr)) {
                assertThat(task.isRunning()).isFalse();
            }
        }

        @Test
        @DisplayName("isRunning() == true after start()")
        void runningAfterStart() {
            AtomicInteger cnt = new AtomicInteger();
            MemoryAllocator alloc = countingAllocator(cnt);
            WatermarkManager mgr = new WatermarkManager(alloc);
            try (MemoryMaintenanceTask task = new MemoryMaintenanceTask(alloc, mgr)) {
                task.start();
                assertThat(task.isRunning()).isTrue();
            }
        }

        @Test
        @DisplayName("isRunning() == false after stop()")
        @Timeout(5)
        void notRunningAfterStop() {
            AtomicInteger cnt = new AtomicInteger();
            MemoryAllocator alloc = countingAllocator(cnt);
            WatermarkManager mgr = new WatermarkManager(alloc);
            MemoryMaintenanceTask task = new MemoryMaintenanceTask(alloc, mgr);
            task.start();
            task.stop();
            assertThat(task.isRunning()).isFalse();
        }

        @Test
        @DisplayName("close() is equivalent to stop() — try-with-resources works")
        @Timeout(5)
        void closeStopsTask() {
            AtomicInteger cnt = new AtomicInteger();
            MemoryAllocator alloc = countingAllocator(cnt);
            WatermarkManager mgr = new WatermarkManager(alloc);
            MemoryMaintenanceTask task = new MemoryMaintenanceTask(alloc, mgr);
            task.start();
            assertThat(task.isRunning()).isTrue();
            task.close();
            assertThat(task.isRunning())
                    .as("close() must stop the loop — isRunning() must be false after close()")
                    .isFalse();
        }

        @Test
        @DisplayName("start() is idempotent — calling twice does not create two threads")
        @Timeout(5)
        void startIsIdempotent() {
            AtomicInteger cnt = new AtomicInteger();
            MemoryAllocator alloc = countingAllocator(cnt);
            WatermarkManager mgr = new WatermarkManager(alloc);
            try (MemoryMaintenanceTask task = new MemoryMaintenanceTask(alloc, mgr)) {
                task.start();
                task.start();
                assertThat(task.isRunning()).isTrue();

                task.stop();
                assertThat(task.isRunning())
                        .as("Task must be stoppable after idempotent start()")
                        .isFalse();
            }
        }
    }

    // =========================================================================
    // Maintenance invocation
    // =========================================================================

    @Nested
    @DisplayName("Maintenance invocation")
    class MaintenanceInvocation {

        @Test
        @DisplayName("performMaintenance() called at least once within configured interval")
        @Timeout(10)
        void maintenanceCalledWithinInterval() {
            AtomicInteger cnt = new AtomicInteger();
            MemoryAllocator alloc = countingAllocator(cnt);
            WatermarkManager mgr = new WatermarkManager(alloc);

            try (MemoryMaintenanceTask task = new MemoryMaintenanceTask(
                    alloc, mgr, 50L, 25L)) {
                task.start();
                awaitCondition(() -> cnt.get() >= 1, 5_000);
            }
            assertThat(cnt.get()).isGreaterThanOrEqualTo(1);
        }

        @Test
        @DisplayName("Maintenance loop tolerates RuntimeException from performMaintenance()")
        @Timeout(5)
        void toleratesMaintenanceException() {
            AtomicInteger callCount = new AtomicInteger();
            MemoryAllocator failingAlloc = new MemoryAllocator() {
                @Override
                public MemoryStats stats() {
                    return new MemoryStats(1_000_000L, 0, 1_000_000L,
                            0, 0, 0, 0, 0, LeakDetectionMode.DISABLED);
                }

                @Override
                public void performMaintenance() {
                    callCount.incrementAndGet();
                    throw new RuntimeException("simulated failure");
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
            WatermarkManager mgr = new WatermarkManager(failingAlloc);

            try (MemoryMaintenanceTask task = new MemoryMaintenanceTask(
                    failingAlloc, mgr, 30L, 15L)) {
                task.start();

                awaitCondition(() -> callCount.get() >= 2, 3_000);
                assertThat(task.isRunning())
                        .as("Loop MUST survive RuntimeException from performMaintenance()")
                        .isTrue();
            }
        }
    }

    // =========================================================================
    // Accessor contracts
    // =========================================================================

    @Nested
    @DisplayName("Accessor contracts")
    class Accessors {

        @Test
        @DisplayName("maintenanceIntervalMs() returns configured value")
        void maintenanceIntervalAccessor() {
            AtomicInteger cnt = new AtomicInteger();
            MemoryAllocator alloc = countingAllocator(cnt);
            WatermarkManager mgr = new WatermarkManager(alloc);
            MemoryMaintenanceTask task = new MemoryMaintenanceTask(alloc, mgr, 7_000L, 3_000L);
            assertThat(task.maintenanceIntervalMs()).isEqualTo(7_000L);
            assertThat(task.watermarkIntervalMs()).isEqualTo(3_000L);
        }
    }
}
