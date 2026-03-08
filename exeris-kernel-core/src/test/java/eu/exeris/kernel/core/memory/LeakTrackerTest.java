/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.core.memory;

import eu.exeris.kernel.spi.memory.LeakDetectionMode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.lang.ref.Reference;
import java.util.concurrent.StructuredTaskScope;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * L1 Unit Tests: {@link LeakTracker} — Cleaner-based forensic leak detection.
 *
 * <h2>Testing Strategy</h2>
 * <p>Leak detection relies on the JVM GC to fire the Cleaner callback. To make
 * tests deterministic, we use explicit {@code System.gc()} hints and
 * {@link Reference#reachabilityFence(Object)} to control object lifetime.
 * We then poll the leak count with a bounded retry loop (max 5 seconds) to account
 * for GC non-determinism in CI environments.
 *
 * @since 0.5.0
 */
@DisplayName("L1: LeakTracker")
class LeakTrackerTest {

    // =========================================================================
    // Helpers
    // =========================================================================

    /**
     * A minimal leak-trackable object (does NOT extend AbstractLoanedBuffer —
     * avoids MemorySegment allocation in tests focused purely on LeakTracker).
     */
    private static Object newReferent() {
        return new Object();
    }

    /**
     * Polls until the leak count reaches {@code expected} or {@code timeoutMs} elapses.
     * Uses GC hints to help the Cleaner fire.
     */
    private static void awaitLeakCount(LeakTracker tracker, long expected, long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (tracker.leakCount() < expected && System.currentTimeMillis() < deadline) {
            System.gc();
            LockSupport.parkNanos(50_000_000L);
        }
    }

    // =========================================================================
    // Tests
    // =========================================================================

    @Nested
    @DisplayName("Construction validation")
    class Construction {

        @Test
        @DisplayName("null mode throws IllegalArgumentException")
        void nullModeThrows() {
            assertThatThrownBy(() -> new LeakTracker(null))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("initial leakCount() == 0")
        void initialLeakCountIsZero() {
            assertThat(new LeakTracker(LeakDetectionMode.DISABLED).leakCount()).isZero();
        }

        @Test
        @DisplayName("mode() returns the mode supplied at construction")
        void modeAccessor() {
            assertThat(new LeakTracker(LeakDetectionMode.PARANOID).mode())
                    .isEqualTo(LeakDetectionMode.PARANOID);
        }
    }

    @Nested
    @DisplayName("DISABLED mode — always returns NOOP handle")
    class DisabledMode {

        @Test
        @DisplayName("track() returns NOOP handle — no Cleaner registration")
        void trackReturnsNoop() {
            var tracker = new LeakTracker(LeakDetectionMode.DISABLED);
            Object referent = newReferent();
            LeakTracker.LeakHandle handle = tracker.track(referent, 64, 0, "");
            assertThat(handle).isSameAs(LeakTracker.LeakHandle.NOOP);
        }

        @Test
        @DisplayName("cancel() on NOOP is a no-op — no exception")
        void cancelNoopIsSafe() {
            LeakTracker.LeakHandle.NOOP.cancel();
            LeakTracker.LeakHandle.NOOP.cancel();
            assertThat(new LeakTracker(LeakDetectionMode.DISABLED).leakCount()).isZero();
        }

        @Test
        @DisplayName("leakCount() stays 0 even when referent is GC'd without cancel")
        void leakCountStaysZeroWhenDisabled() {
            var tracker = new LeakTracker(LeakDetectionMode.DISABLED);
            tracker.track(newReferent(), 64, 0, "");
            System.gc();
            LockSupport.parkNanos(200_000_000L);
            assertThat(tracker.leakCount()).isZero();
        }
    }

    @Nested
    @DisplayName("PARANOID mode — all allocations tracked")
    class ParanoidMode {

        @Test
        @DisplayName("properly cancelled handle → leakCount does NOT increment")
        void cancelledHandleDoesNotLeak() {
            var tracker = new LeakTracker(LeakDetectionMode.PARANOID);
            Object referent = newReferent();
            LeakTracker.LeakHandle handle = tracker.track(referent, 64, 0, "stack");
            handle.cancel();
            Reference.reachabilityFence(referent);

            System.gc();
            LockSupport.parkNanos(200_000_000L);
            assertThat(tracker.leakCount()).isZero();
        }

        @Test
        @DisplayName("uncancelled handle → leakCount increments after GC")
        @Timeout(10)
        void uncancelledHandleIncrementsLeakCount() {
            var tracker = new LeakTracker(LeakDetectionMode.PARANOID);

            trackAndDropWithoutCancel(tracker);

            awaitLeakCount(tracker, 1, 5_000);
            assertThat(tracker.leakCount()).isGreaterThanOrEqualTo(1);
        }

        /**
         * Separate method so the local variable holding the referent goes out of scope.
         * This is critical: if the reference is still on the stack, the object won't be GC'd.
         */
        private static void trackAndDropWithoutCancel(LeakTracker tracker) {
            Object referent = new Object();
            tracker.track(referent, 128, 0xdeadbee, "fake\n\tstack\n\ttrace");
        }

        @Test
        @DisplayName("cancel() is idempotent — calling twice does not throw")
        void cancelIsIdempotent() {
            var tracker = new LeakTracker(LeakDetectionMode.PARANOID);
            Object referent = newReferent();
            LeakTracker.LeakHandle handle = tracker.track(referent, 64, 0, "");
            handle.cancel();
            handle.cancel();
            Reference.reachabilityFence(referent);
            assertThat(tracker.leakCount())
                    .as("Double cancel must not corrupt the leak counter")
                    .isZero();
        }

        @Test
        @DisplayName("multiple concurrent tracked objects — each leak counted independently")
        @Timeout(15)
        void multipleConcurrentLeaks() {
            var tracker = new LeakTracker(LeakDetectionMode.PARANOID);
            int leakCount = 3;
            trackNObjectsWithoutCancel(tracker, leakCount);
            awaitLeakCount(tracker, leakCount, 8_000);
            assertThat(tracker.leakCount()).isGreaterThanOrEqualTo(leakCount);
        }

        private static void trackNObjectsWithoutCancel(LeakTracker tracker, int n) {
            for (int i = 0; i < n; i++) {
                tracker.track(new Object(), 64, i, "");
            }
        }
    }

    @Nested
    @DisplayName("SAMPLED mode — ~1/128 allocations tracked")
    class SampledMode {

        @Test
        @DisplayName("Most track() calls return NOOP (sampling rate ~1/128)")
        void mostCallsReturnNoop() {
            var tracker = new LeakTracker(LeakDetectionMode.SAMPLED);
            int total = 512;
            int noops = 0;
            for (int i = 0; i < total; i++) {
                LeakTracker.LeakHandle handle = tracker.track(new Object(), 64, i, "");
                if (handle == LeakTracker.LeakHandle.NOOP) {
                    noops++;
                } else {
                    handle.cancel();
                }
            }
            assertThat(noops).as("Expected most calls to return NOOP in SAMPLED mode")
                    .isGreaterThanOrEqualTo(total / 2);
        }

        @Test
        @DisplayName("At least one non-NOOP handle is returned in 512 calls")
        void atLeastOneTrackedAllocation() {
            var tracker = new LeakTracker(LeakDetectionMode.SAMPLED);
            int nonNoops = 0;
            for (int i = 0; i < 512; i++) {
                LeakTracker.LeakHandle handle = tracker.track(new Object(), 64, i, "");
                if (handle != LeakTracker.LeakHandle.NOOP) {
                    nonNoops++;
                    handle.cancel();
                }
            }
            assertThat(nonNoops).as("Expected at least one sampled allocation in 512 calls")
                    .isGreaterThanOrEqualTo(1);
        }
    }

    @Nested
    @DisplayName("Thread safety — concurrent track() and cancel() under Virtual Threads")
    class ThreadSafety {

        @Test
        @DisplayName("1000 concurrent VTs each tracking and cancelling — leakCount == 0")
        @Timeout(30)
        void concurrentTrackAndCancelNoLeaks() throws InterruptedException {
            var tracker = new LeakTracker(LeakDetectionMode.PARANOID);
            var errorFlag = new AtomicLong(0);

            try (var scope = StructuredTaskScope.open(
                    StructuredTaskScope.Joiner.<Void>awaitAllSuccessfulOrThrow())) {
                for (int i = 0; i < 1_000; i++) {
                    scope.fork(() -> {
                        Object referent = new Object();
                        LeakTracker.LeakHandle handle = tracker.track(referent, 64, 0, "");
                        handle.cancel();
                        Reference.reachabilityFence(referent);
                        return null;
                    });
                }
                scope.join();
            }

            System.gc();
            LockSupport.parkNanos(200_000_000L);
            assertThat(tracker.leakCount()).isZero();
            assertThat(errorFlag.get()).isZero();
        }
    }
}
