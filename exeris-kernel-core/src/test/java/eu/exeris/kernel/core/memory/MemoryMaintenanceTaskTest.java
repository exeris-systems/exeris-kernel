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
import jdk.jfr.Recording;
import jdk.jfr.consumer.RecordingFile;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
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

    // =========================================================================
    // Refresh-only variant
    // =========================================================================

    @Nested
    @DisplayName("Refresh-only variant — watermarkRefreshOnly")
    @Timeout(60)
    class WatermarkRefreshOnly {

        private static final long TOTAL_BYTES = 1_000_000L;
        private static final long REFRESH_INTERVAL_MS = 1L;
        private static final int PERMITS_PER_CHECK = 3;
        private static final long AWAIT_SECONDS = 20L;
        private static final String MAINTENANCE_CYCLE_EVENT = "eu.exeris.kernel.core.MemoryMaintenanceCycle";
        private static final String MAINTENANCE_FAILURE_EVENT = "eu.exeris.kernel.core.MemoryMaintenanceFailure";

        /**
         * An allocator whose utilisation is settable and which hands out one permit at the end of each
         * {@code stats()} served on the refresh thread, so a test waits on the refresh rather than on a clock.
         */
        private final class SignallingAllocator implements MemoryAllocator {
            private final AtomicInteger maintenanceCalls = new AtomicInteger();
            private final Semaphore refreshes = new Semaphore(0);
            private final AtomicReference<Thread> refreshThread = new AtomicReference<>();
            private volatile long allocatedBytes;

            void utilisationPct(int pct) {
                allocatedBytes = TOTAL_BYTES / 100L * pct;
            }

            @Override
            public MemoryStats stats() {
                MemoryStats stats = new MemoryStats(TOTAL_BYTES, allocatedBytes, TOTAL_BYTES - allocatedBytes,
                        0, 0, allocatedBytes, 0, 0, LeakDetectionMode.DISABLED);
                Thread current = Thread.currentThread();
                if (MemoryMaintenanceTask.WATERMARK_REFRESH_THREAD_NAME.equals(current.getName())) {
                    refreshThread.set(current);
                    refreshes.release();
                }
                return stats;
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
            public void close() {
                // No-op: stub allocator holds no off-heap resources
            }

            boolean awaitRefreshes(int count) throws InterruptedException {
                return refreshes.tryAcquire(count, AWAIT_SECONDS, TimeUnit.SECONDS);
            }
        }

        @Test
        @DisplayName("a pressure change reaches currentLevel() through the refresh thread alone")
        void refreshesTheLevel() throws InterruptedException {
            SignallingAllocator allocator = new SignallingAllocator();
            WatermarkManager manager = new WatermarkManager(allocator);
            try (MemoryMaintenanceTask task =
                         MemoryMaintenanceTask.watermarkRefreshOnly(manager, REFRESH_INTERVAL_MS)) {
                task.start();
                allocator.utilisationPct(97);
                allocator.refreshes.drainPermits();

                assertThat(allocator.awaitRefreshes(PERMITS_PER_CHECK)).isTrue();

                assertThat(manager.currentLevel()).isEqualTo(WatermarkLevel.SHEDDING);
            }
        }

        @Test
        @DisplayName("never attempts allocator maintenance: no maintenance cycle or failure is recorded")
        void neverRunsAllocatorMaintenance() throws Exception {
            SignallingAllocator allocator = new SignallingAllocator();
            WatermarkManager manager = new WatermarkManager(allocator);
            Path dump = Files.createTempFile("watermark-refresh-only", ".jfr");
            try (Recording recording = new Recording()) {
                recording.enable(MAINTENANCE_CYCLE_EVENT);
                recording.enable(MAINTENANCE_FAILURE_EVENT);
                recording.start();
                MemoryMaintenanceTask task =
                        MemoryMaintenanceTask.watermarkRefreshOnly(manager, REFRESH_INTERVAL_MS);
                try {
                    task.start();
                    assertThat(allocator.awaitRefreshes(PERMITS_PER_CHECK)).isTrue();
                } finally {
                    task.stop();
                }
                recording.dump(dump);

                assertThat(allocator.maintenanceCalls).hasValue(0);
                assertThat(RecordingFile.readAllEvents(dump).stream()
                        .map(event -> event.getEventType().getName())
                        .filter(name -> name.equals(MAINTENANCE_CYCLE_EVENT)
                                || name.equals(MAINTENANCE_FAILURE_EVENT)))
                        .as("a maintenance attempt, even one that fails, leaves an event")
                        .isEmpty();
            } finally {
                Files.deleteIfExists(dump);
            }
        }

        @Test
        @DisplayName("stop() ends the thread, a second stop() is a no-op, and start() refreshes again")
        void stopAndRestart() throws InterruptedException {
            SignallingAllocator allocator = new SignallingAllocator();
            WatermarkManager manager = new WatermarkManager(allocator);
            MemoryMaintenanceTask task = MemoryMaintenanceTask.watermarkRefreshOnly(manager, REFRESH_INTERVAL_MS);
            try {
                task.start();
                assertThat(allocator.awaitRefreshes(1)).isTrue();
                Thread first = allocator.refreshThread.get();

                task.stop();

                assertThat(task.isRunning()).isFalse();
                assertThat(first.isAlive()).as("the refresh thread has ended").isFalse();
                task.stop();
                assertThat(task.isRunning()).isFalse();

                allocator.refreshes.drainPermits();
                task.start();
                assertThat(task.isRunning()).isTrue();
                assertThat(allocator.awaitRefreshes(PERMITS_PER_CHECK)).as("refreshing again").isTrue();
            } finally {
                task.stop();
            }
        }

        @Test
        @DisplayName("a null manager or a non-positive interval is refused; the maintenance interval reads 0")
        void validation() {
            WatermarkManager manager = new WatermarkManager(new SignallingAllocator());

            assertThatThrownBy(() -> MemoryMaintenanceTask.watermarkRefreshOnly(null, 1_000L))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> MemoryMaintenanceTask.watermarkRefreshOnly(manager, 0L))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> MemoryMaintenanceTask.watermarkRefreshOnly(manager, -5L))
                    .isInstanceOf(IllegalArgumentException.class);
            MemoryMaintenanceTask task = MemoryMaintenanceTask.watermarkRefreshOnly(manager, 1_000L);
            assertThat(task.maintenanceIntervalMs()).isZero();
            assertThat(task.watermarkIntervalMs()).isEqualTo(1_000L);
        }
    }
}
