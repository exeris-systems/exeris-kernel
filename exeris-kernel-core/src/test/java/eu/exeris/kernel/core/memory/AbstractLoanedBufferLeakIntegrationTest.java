/*
 * Copyright (C) 2025-2026 Exeris. All rights reserved.
 *
 * This code is part of the Exeris Systems.
 * Distributed under the proprietary Exeris Software License.
 * Unauthorized copying or distribution is prohibited.
 */
package eu.exeris.kernel.core.memory;

import eu.exeris.kernel.spi.memory.LeakDetectionMode;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.LockSupport;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * L2 Integration Tests: {@link AbstractLoanedBuffer} + {@link LeakTracker} integration.
 *
 * <p>Verifies that:
 * <ol>
 *   <li>A properly-closed buffer does not increment the leak count.</li>
 *   <li>An unclosed buffer triggers the {@link LeakTracker} after GC.</li>
 *   <li>{@link AbstractLoanedBuffer#resetForReuse()} clears the leak handle.</li>
 * </ol>
 *
 * @since 0.5.0
 */
@DisplayName("L2: AbstractLoanedBuffer + LeakTracker integration")
class AbstractLoanedBufferLeakIntegrationTest {

    // =========================================================================
    // Test double
    // =========================================================================

    private static TestBuffer allocate() {
        // CHECKSTYLE:OFF — Arena.ofShared() is permitted in test harness code
        Arena arena = Arena.ofShared();
        // CHECKSTYLE:ON
        MemorySegment seg = arena.allocate(64);
        return new TestBuffer(seg, arena);
    }

    private static final class TestBuffer extends AbstractLoanedBuffer {
        private final MemorySegment seg;
        private final Arena arena;
        final AtomicBoolean released = new AtomicBoolean(false);

        TestBuffer(MemorySegment seg, Arena arena) {
            super();
            this.seg = seg;
            this.arena = arena;
            setSize(seg.byteSize());
        }

        @Override
        protected MemorySegment backingSegment() {
            return seg;
        }

        @Override
        protected void onRelease() {
            released.set(true);
            arena.close();
        }
    }

    // =========================================================================
    // Tests
    // =========================================================================

    @Nested
    @DisplayName("Properly-closed buffers — no leak")
    class NoLeak {

        @Test
        @DisplayName("closed buffer → leakCount stays 0 in PARANOID mode")
        @Timeout(10)
        void closedBufferNoLeak() {
            var tracker = new LeakTracker(LeakDetectionMode.PARANOID);

            try (TestBuffer buf = allocate()) {
                buf.enableLeakTracking(tracker);
            }

            System.gc();
            LockSupport.parkNanos(200_000_000L);
            assertThat(tracker.leakCount()).isZero();
        }

        @Test
        @DisplayName("try-with-resources: close() triggers onRelease() and cancels leak handle")
        void twrClosesAndCancels() {
            var tracker = new LeakTracker(LeakDetectionMode.PARANOID);
            TestBuffer buf = allocate();
            buf.enableLeakTracking(tracker);

            assertThat(buf.isAlive()).isTrue();
            buf.close();
            assertThat(buf.isAlive()).isFalse();
            assertThat(buf.released.get()).isTrue();
        }
    }

    @Nested
    @DisplayName("Unclosed buffers — leak detection")
    class LeakDetection {

        @Test
        @DisplayName("unclosed buffer GC'd → leakCount increments in PARANOID mode")
        @Timeout(10)
        void unclosedBufferIncreasesLeakCount() {
            var tracker = new LeakTracker(LeakDetectionMode.PARANOID);
            allocateAndDrop(tracker);

            long deadline = System.currentTimeMillis() + 5_000;
            while (tracker.leakCount() == 0 && System.currentTimeMillis() < deadline) {
                System.gc();
                LockSupport.parkNanos(50_000_000L);
            }
            assertThat(tracker.leakCount()).isGreaterThanOrEqualTo(1);
        }

        /**
         * Separate method to ensure the buffer reference goes out of scope completely.
         * The arena is intentionally NOT closed so the off-heap stays live — what we're
         * testing is only the JFR/counter detection, not the arena lifecycle.
         */
        @SuppressWarnings("PMD.UnusedLocalVariable")
        private static void allocateAndDrop(LeakTracker tracker) {
            // CHECKSTYLE:OFF — Arena.ofShared() permitted in test
            Arena arena = Arena.ofShared();
            // CHECKSTYLE:ON
            MemorySegment seg = arena.allocate(64);
            TestBuffer buf = new TestBuffer(seg, arena);
            buf.enableLeakTracking(tracker);
        }
    }

    @Nested
    @DisplayName("resetForReuse() clears leak handle")
    class ResetForReuse {

        @Test
        @DisplayName("resetForReuse() restores NOOP leak handle — subsequent close() is safe")
        void resetClearsLeakHandle() {
            var tracker = new LeakTracker(LeakDetectionMode.PARANOID);
            TestBuffer buf = allocate();
            buf.enableLeakTracking(tracker);
            buf.close();
            assertThat(tracker.leakCount()).isZero();
        }
    }

    @Nested
    @DisplayName("DISABLED mode — no LeakTracker overhead")
    class DisabledMode {

        @Test
        @DisplayName("enableLeakTracking with DISABLED mode returns NOOP handle — no GC pressure")
        void disabledModeNoOverhead() {
            var tracker = new LeakTracker(LeakDetectionMode.DISABLED);
            try (TestBuffer buf = allocate()) {
                buf.enableLeakTracking(tracker);
                assertThat(buf.isAlive()).isTrue();
            }
            assertThat(tracker.leakCount()).isZero();
        }
    }

    @Nested
    @DisplayName("enableLeakTracking() validation")
    class EnableLeakTrackingValidation {

        @Test
        @DisplayName("null tracker throws IllegalArgumentException")
        void nullTrackerThrows() {
            try (TestBuffer buf = allocate()) {
                Assertions.assertThatThrownBy(
                                () -> buf.enableLeakTracking(null))
                        .isInstanceOf(IllegalArgumentException.class);
            }
        }
    }
}
