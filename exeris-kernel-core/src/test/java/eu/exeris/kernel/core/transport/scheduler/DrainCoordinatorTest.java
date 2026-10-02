/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.core.transport.scheduler;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The counter a graceful shutdown waits on (issue #282).
 *
 * <p>Its invariants are cheap to state and expensive to get wrong: an over-decrement makes the drain
 * finish early and sever a live exchange, an under-decrement makes it hang to the deadline and get
 * SIGKILLed. The two socket-level tests in the transport TCK and the Community HTTP module prove the
 * mechanism end to end but cannot isolate the arithmetic from stream timing, so it is pinned here.
 */
@DisplayName("DrainCoordinator — what a graceful shutdown waits on")
class DrainCoordinatorTest {

    @Nested
    @DisplayName("Busy accounting")
    class BusyAccounting {

        @Test
        @DisplayName("a fresh coordinator has nothing to wait for")
        void startsEmpty() {
            assertThat(new DrainCoordinator().busyStreams()).isZero();
        }

        @Test
        @DisplayName("a registered stream is busy before its protocol says anything")
        void registeredStreamIsBusyByDefault() {
            DrainCoordinator coordinator = new DrainCoordinator();
            coordinator.registerStream();

            assertThat(coordinator.busyStreams())
                    .as("busy by default is the load-bearing direction: a protocol that reports "
                            + "nothing must still hold the drain, or teardown severs a handler "
                            + "still writing its response")
                    .isEqualTo(1);
        }

        @Test
        @DisplayName("markIdle() twice does not decrement twice")
        void markIdleIsIdempotent() {
            DrainCoordinator coordinator = new DrainCoordinator();
            DrainCoordinator.StreamWork first = coordinator.registerStream();
            coordinator.registerStream();

            first.markIdle();
            first.markIdle();

            assertThat(coordinator.busyStreams())
                    .as("a double decrement would drop the count below the work actually in flight, "
                            + "and the drain would finish while another stream is still being served")
                    .isEqualTo(1);
        }

        @Test
        @DisplayName("markBusy() after markIdle() puts the stream back on the drain's books")
        void markBusyRestoresTheStream() {
            DrainCoordinator coordinator = new DrainCoordinator();
            DrainCoordinator.StreamWork work = coordinator.registerStream();

            work.markIdle();
            work.markBusy();

            assertThat(coordinator.busyStreams())
                    .as("this is the keep-alive loop taking the next request: parked it is idle, "
                            + "reading a request it is busy again")
                    .isEqualTo(1);
        }

        @Test
        @DisplayName("markBusy() twice does not increment twice")
        void markBusyIsIdempotent() {
            DrainCoordinator coordinator = new DrainCoordinator();
            DrainCoordinator.StreamWork work = coordinator.registerStream();

            work.markBusy();
            work.markBusy();

            assertThat(coordinator.busyStreams())
                    .as("an inflated count never reaches zero, so the drain waits out its full "
                            + "deadline and the runtime SIGKILLs a shutdown that was already done")
                    .isEqualTo(1);
        }

        @Test
        @DisplayName("close() after markIdle() is a no-op")
        void closeAfterMarkIdleDoesNotDecrementAgain() {
            DrainCoordinator coordinator = new DrainCoordinator();
            DrainCoordinator.StreamWork first = coordinator.registerStream();
            coordinator.registerStream();

            first.markIdle();
            first.close();

            assertThat(coordinator.busyStreams())
                    .as("try-with-resources closes a handle the protocol loop already reported idle; "
                            + "that is the normal path, not an error path")
                    .isEqualTo(1);
        }

        @Test
        @DisplayName("close() releases a stream that never reported itself idle")
        void closeReleasesABusyStream() {
            DrainCoordinator coordinator = new DrainCoordinator();
            DrainCoordinator.StreamWork work = coordinator.registerStream();

            work.close();

            assertThat(coordinator.busyStreams())
                    .as("a raw handler stays busy for its whole life and is released only on exit")
                    .isZero();
        }

        @Test
        @DisplayName("streams account for themselves independently")
        void streamsAreIndependent() {
            DrainCoordinator coordinator = new DrainCoordinator();
            DrainCoordinator.StreamWork first = coordinator.registerStream();
            DrainCoordinator.StreamWork second = coordinator.registerStream();
            DrainCoordinator.StreamWork third = coordinator.registerStream();

            first.markIdle();
            third.markIdle();

            assertThat(coordinator.busyStreams()).isEqualTo(1);

            second.close();
            assertThat(coordinator.busyStreams()).isZero();
        }
    }

    @Nested
    @DisplayName("Draining flag")
    class DrainingFlag {

        @Test
        @DisplayName("a running engine is not draining")
        void notDrainingUntilMarked() {
            assertThat(new DrainCoordinator().isDraining()).isFalse();
        }

        @Test
        @DisplayName("markDraining() is one-way")
        void drainingIsMonotonic() {
            DrainCoordinator coordinator = new DrainCoordinator();

            coordinator.markDraining();
            coordinator.markDraining();

            assertThat(coordinator.isDraining())
                    .as("shutdown never un-starts; a protocol that saw the flag must not later be "
                            + "told the connection may be kept alive after all")
                    .isTrue();
        }

        @Test
        @DisplayName("draining does not by itself change what the drain waits for")
        void drainingDoesNotTouchTheBusyCount() {
            DrainCoordinator coordinator = new DrainCoordinator();
            coordinator.registerStream();

            coordinator.markDraining();

            assertThat(coordinator.busyStreams())
                    .as("marking the drain announces shutdown; it does not abandon work in flight")
                    .isEqualTo(1);
        }
    }

    @Nested
    @DisplayName("Sealing — observing zero and committing must be one step")
    class Sealing {

        @Test
        @DisplayName("an idle coordinator seals")
        void idleCoordinatorSeals() {
            assertThat(new DrainCoordinator().sealIfIdle()).isTrue();
        }

        @Test
        @DisplayName("a coordinator with work in flight refuses to seal")
        void busyCoordinatorDoesNotSeal() {
            DrainCoordinator coordinator = new DrainCoordinator();
            coordinator.registerStream();

            assertThat(coordinator.sealIfIdle())
                    .as("sealing while a stream is served is the commit that severs it")
                    .isFalse();
        }

        @Test
        @DisplayName("after sealing, a parked stream cannot re-arm")
        void sealedCoordinatorRefusesReArm() {
            DrainCoordinator coordinator = new DrainCoordinator();
            DrainCoordinator.StreamWork work = coordinator.registerStream();
            work.markIdle();

            assertThat(coordinator.sealIfIdle()).isTrue();

            assertThat(work.markBusy())
                    .as("this is the window the drain loop could not close by reading the count: a "
                            + "request arriving after the commit must be told to close, not served "
                            + "by a handler the teardown is about to sever")
                    .isFalse();
            assertThat(coordinator.busyStreams())
                    .as("and a refused re-arm must not leave a count behind either")
                    .isZero();
        }

        @Test
        @DisplayName("a stream registered after the seal holds no count")
        void streamRegisteredAfterSealHoldsNoCount() {
            DrainCoordinator coordinator = new DrainCoordinator();
            assertThat(coordinator.sealIfIdle()).isTrue();

            DrainCoordinator.StreamWork late = coordinator.registerStream();

            assertThat(coordinator.busyStreams())
                    .as("close() does not stop admission, so a connection can still arrive — but it "
                            + "cannot extend a shutdown already past its commit point")
                    .isZero();
            assertThat(late.markBusy()).isFalse();
            late.close();
            assertThat(coordinator.busyStreams())
                    .as("and closing a handle that never took a count must not decrement")
                    .isZero();
        }

        @Test
        @DisplayName("sealNow discards work in flight, for the drain deadline only")
        void sealNowDiscardsInFlightWork() {
            DrainCoordinator coordinator = new DrainCoordinator();
            coordinator.registerStream();

            coordinator.sealNow();

            assertThat(coordinator.busyStreams()).isZero();
            // Asserted through a FRESH handle. Asking the already-busy one whether it may re-arm
            // answers from its own flag before the coordinator is ever consulted, so it would pass
            // against no seal at all.
            assertThat(coordinator.registerStream().markBusy())
                    .as("once shutdown proceeds regardless, letting a late arrival believe it is "
                            + "protected is worse than telling it to close")
                    .isFalse();
        }

        /**
         * The forced seal is the one path where a handle outlives its count, and the marker is a number.
         *
         * <p>A handler still running when the deadline fires finishes afterwards and releases, and
         * {@code release()} decrements unconditionally. That is safe only because the seal marks a
         * <em>range</em>: the decrement lands further inside it. Pin an exact marker instead — the
         * shape this suite was first written against, at {@code Integer.MIN_VALUE} — and one decrement
         * wraps it to {@code Integer.MAX_VALUE}, which reads as neither sealed nor a plausible count.
         * The coordinator resumes handing out counts and a stream can report itself busy in the middle
         * of teardown: the failure this class exists to prevent, reopened through the path that exists
         * for it.
         *
         * <p>Deterministic, not an interleaving: it fires on every timed-out drain with work in flight.
         * The single-release case is kept alongside {@link #sealSurvivesRepeatedPostSealReleases()}
         * because it is the minimal one — if the range property breaks, this is what fails first.
         */
        @Test
        @DisplayName("a stream released after a forced seal does not corrupt the sentinel")
        void releaseAfterForcedSealDoesNotCorruptTheCount() {
            DrainCoordinator coordinator = new DrainCoordinator();
            DrainCoordinator.StreamWork inFlight = coordinator.registerStream();

            coordinator.sealNow();
            inFlight.close();

            assertThat(coordinator.busyStreams())
                    .as("a wrapped sentinel reads as a huge live count, so shutdown would believe "
                            + "work appeared out of nowhere at the moment it stopped waiting")
                    .isZero();
            assertThat(coordinator.registerStream().markBusy())
                    .as("and the seal must survive the release that followed it")
                    .isFalse();
        }

        /**
         * The marker is a range, and the headroom is the reason the hot path can stay on
         * {@code getAndAdd}.
         *
         * <p>Post-seal releases drive the count further negative, so the claim being pinned is that a
         * realistic number of them cannot walk it back across zero. The streams must be registered
         * <em>before</em> the seal to hold a count at all — one registered after holds none, so its
         * {@code close()} is a no-op and would exercise nothing. Asserting a single release proves only
         * the first step.
         */
        @Test
        @DisplayName("the seal survives far more post-seal releases than any ceiling permits")
        void sealSurvivesRepeatedPostSealReleases() {
            DrainCoordinator coordinator = new DrainCoordinator();
            List<DrainCoordinator.StreamWork> inFlight = new ArrayList<>();
            for (int i = 0; i < 10_000; i++) {
                inFlight.add(coordinator.registerStream());
            }

            coordinator.sealNow();
            inFlight.forEach(DrainCoordinator.StreamWork::close);

            assertThat(coordinator.busyStreams())
                    .as("a marker one step from wrapping reports a sealed engine as fully loaded")
                    .isZero();
            assertThat(coordinator.registerStream().markBusy())
                    .as("and admission must still be closed after all of it")
                    .isFalse();
        }
    }

    /**
     * The handle is reachable from more than its own thread, so its flag is a compare-and-set.
     *
     * <p>These cannot prove the absence of a race — that needs a stress harness, and jcstress is not
     * wired into this repo. What they do prove is that the previous shape was wrong: reverting the flag
     * to a non-volatile field with read-modify-write fails <em>all three</em> of these, observed rather
     * than assumed (the double-acquire case reported a count of 3 against an expected 1). A green run
     * here is evidence the arithmetic holds under contention, not a proof that it always will; that
     * distinction is written down rather than left for a reader to assume the stronger claim.
     */
    @Nested
    @DisplayName("Handle concurrency")
    class HandleConcurrency {

        private static final int THREADS = 8;
        private static final int ROUNDS = 2_000;

        @Test
        @DisplayName("concurrent markIdle() releases the count once, never once per caller")
        void concurrentMarkIdleNeverDoubleReleases() throws InterruptedException {
            for (int round = 0; round < ROUNDS; round++) {
                DrainCoordinator coordinator = new DrainCoordinator();
                DrainCoordinator.StreamWork work = coordinator.registerStream();

                raceOn(work::markIdle);

                assertThat(coordinator.busyStreams())
                        .as("a double release drops the count below the truth, so the drain finishes "
                                + "early and teardown severs a handler still writing its response")
                        .isZero();
            }
        }

        @Test
        @DisplayName("concurrent markBusy() takes the count once, never once per caller")
        void concurrentMarkBusyNeverDoubleAcquires() throws InterruptedException {
            for (int round = 0; round < ROUNDS; round++) {
                DrainCoordinator coordinator = new DrainCoordinator();
                DrainCoordinator.StreamWork work = coordinator.registerStream();
                work.markIdle();

                raceOn(work::markBusy);

                assertThat(coordinator.busyStreams())
                        .as("a double acquire leaves a count nothing will ever release, so the drain "
                                + "burns its full deadline — issue #282's symptom by another route")
                        .isEqualTo(1);
            }
        }

        @Test
        @DisplayName("interleaved markIdle()/markBusy() leaves the count matching the flag")
        void interleavedTransitionsPreserveTheInvariant() throws InterruptedException {
            for (int round = 0; round < ROUNDS; round++) {
                DrainCoordinator coordinator = new DrainCoordinator();
                DrainCoordinator.StreamWork work = coordinator.registerStream();

                raceOn(index -> {
                    if (index % 2 == 0) {
                        work.markIdle();
                    } else {
                        work.markBusy();
                    }
                });

                // Whichever transition landed last, the handle holds 0 or 1 — never 2, never -1.
                work.markIdle();
                assertThat(coordinator.busyStreams())
                        .as("the invariant is that a handle holds exactly busy ? 1 : 0 counts; any "
                                + "other value means a transition and its counter operation came apart")
                        .isZero();
            }
        }

        private void raceOn(Runnable action) throws InterruptedException {
            raceOn(_ -> action.run());
        }

        private void raceOn(java.util.function.IntConsumer action) throws InterruptedException {
            java.util.concurrent.CountDownLatch start = new java.util.concurrent.CountDownLatch(1);
            Thread[] threads = new Thread[THREADS];
            for (int i = 0; i < THREADS; i++) {
                int index = i;
                threads[i] = Thread.ofVirtual().unstarted(() -> {
                    try {
                        start.await();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                    action.accept(index);
                });
                threads[i].start();
            }
            start.countDown();
            for (Thread thread : threads) {
                thread.join();
            }
        }
    }
}
