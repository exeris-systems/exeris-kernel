/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.community.persistence;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * L1 Unit: {@link FairnessTracker} — fairness tracking and admission control.
 *
 * @since 0.6.0
 */
@DisplayName("L1 Unit: FairnessTracker")
class FairnessTrackerTest {

    @Nested
    @DisplayName("Fairness ratio tracking")
    class FairnessRatioTracking {

        @Test
        @DisplayName("Returns 1.0 when all decisions are accepted")
        void returnsOne_whenAllAccepted() {
            FairnessTracker tracker = new FairnessTracker();
            for (int i = 0; i < 100; i++) {
                tracker.recordDecision(true, i % 10);
            }
            double fairness = tracker.computeFairnessRatio();
            assertThat(fairness).isCloseTo(1.0, within(0.05d));
        }

        @Test
        @DisplayName("Returns 0.0 when all decisions are rejected")
        void returnsZero_whenAllRejected() {
            FairnessTracker tracker = new FairnessTracker();
            for (int i = 0; i < 100; i++) {
                tracker.recordDecision(false, i % 10);
            }
            double fairness = tracker.computeFairnessRatio();
            assertThat(fairness).isCloseTo(0.0, within(0.05d));
        }

        @Test
        @DisplayName("Returns 1.0 when no decisions recorded")
        void returnsOne_whenNoData() {
            FairnessTracker tracker = new FairnessTracker();
            double fairness = tracker.computeFairnessRatio();
            assertThat(fairness).isEqualTo(1.0);
        }
    }

    @Nested
    @DisplayName("Queue depth tracking")
    class QueueDepthTracking {

        @Test
        @DisplayName("Computes queue depth p95 from histogram instead of max")
        void computesQueueDepthP95FromHistogram() {
            FairnessTracker tracker = new FairnessTracker();

            for (int i = 0; i < 95; i++) {
                tracker.recordDecision(true, 1);
            }
            for (int i = 0; i < 5; i++) {
                tracker.recordDecision(true, 40);
            }

            long p95 = tracker.computeQueueDepthP95();
            assertThat(p95).isEqualTo(1L);
        }

        @Test
        @DisplayName("Returns 0 when no queue depth recorded")
        void returnsZero_whenNoQueueData() {
            FairnessTracker tracker = new FairnessTracker();
            long p95 = tracker.computeQueueDepthP95();
            assertThat(p95).isZero();
        }

        @Test
        @DisplayName("Uses only recent 10-second window for fairness and queue depth")
        void usesRecentWindow_only() {
            AtomicLong now = new AtomicLong(0L);
            FairnessTracker tracker = new FairnessTracker(now::get);

            for (int i = 0; i < 20; i++) {
                tracker.recordDecision(false, 9);
            }

            now.set(11_000_000_000L);
            for (int i = 0; i < 10; i++) {
                tracker.recordDecision(true, 1);
            }

            assertThat(tracker.computeFairnessRatio()).isCloseTo(1.0d, within(0.05d));
            assertThat(tracker.computeQueueDepthP95()).isEqualTo(1L);
        }

        @Test
        @DisplayName("Tracks queue wait p95 only while queue is present")
        void tracksQueueWaitP95_whenQueued() {
            AtomicLong now = new AtomicLong(0L);
            FairnessTracker tracker = new FairnessTracker(now::get);

            tracker.recordDecision(true, 0);
            now.set(20_000_000L);
            tracker.recordDecision(true, 2);
            now.set(40_000_000L);
            tracker.recordDecision(false, 3);
            now.set(60_000_000L);
            tracker.recordDecision(true, 0);

            assertThat(tracker.computeQueueWaitP95Ms()).isEqualTo(20L);
        }

        @Test
        @DisplayName("Refreshes cached admission stress snapshot on decision cadence")
        void refreshesStressSnapshot_onDecisionCadence() {
            AtomicLong now = new AtomicLong(0L);
            FairnessTracker tracker = new FairnessTracker(now::get);

            for (int i = 0; i < 40; i++) {
                tracker.recordDecision(false, 2);
            }

            assertThat(tracker.indicatesAdmissionStress(0.90d, 1L)).isTrue();

            for (int i = 0; i < 400; i++) {
                tracker.recordDecision(true, 0);
            }

            assertThat(tracker.indicatesAdmissionStress(0.90d, 1L)).isFalse();
        }

        @Test
        @DisplayName("Returns zero queue wait p95 when queue never formed")
        void returnsZeroQueueWaitP95_whenNoQueue() {
            FairnessTracker tracker = new FairnessTracker();
            for (int i = 0; i < 32; i++) {
                tracker.recordDecision(true, 0);
            }
            assertThat(tracker.computeQueueWaitP95Ms()).isZero();
        }
    }

    @Nested
    @DisplayName("Thread safety")
    class ThreadSafety {

        @Test
        @DisplayName("Handles concurrent recordDecision calls")
        void handlesConcurrentRecords() throws InterruptedException {
            FairnessTracker tracker = new FairnessTracker();
            int threadCount = 8;
            int recordsPerThread = 100;
            Thread[] threads = new Thread[threadCount];

            for (int t = 0; t < threadCount; t++) {
                threads[t] = new Thread(() -> {
                    for (int i = 0; i < recordsPerThread; i++) {
                        tracker.recordDecision(true, i % 10);
                    }
                });
                threads[t].start();
            }

            for (Thread t : threads) {
                t.join();
            }

            double fairness = tracker.computeFairnessRatio();
            assertThat(fairness).isCloseTo(1.0, within(0.05d));
        }
    }
}
