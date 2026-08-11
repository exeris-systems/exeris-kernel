/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.community.scheduling;

import eu.exeris.kernel.spi.context.KernelProviders;
import eu.exeris.kernel.spi.scheduling.JobDescriptor;
import eu.exeris.kernel.spi.scheduling.JobScheduler;
import eu.exeris.kernel.spi.scheduling.JobSchedulerConfig;
import eu.exeris.kernel.spi.scheduling.JobState;
import eu.exeris.kernel.spi.scheduling.JobTrigger;
import eu.exeris.kernel.spi.security.ImmutableStorageContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What a job stops holding, and when — the settle half of the scheduler's lifecycle.
 *
 * <h2>Why these assertions reach past the SPI</h2>
 * <p>{@link eu.exeris.kernel.spi.scheduling.JobHandle} deliberately keeps answering after a job ends
 * (ADR-057 §6), so at the SPI level a settled job and one that merely finished look identical. The
 * difference is what the handle still points at: the application's job body and the submitter's
 * captured identity. Nothing in the public contract can see that, which is why these are
 * package-local — the alternative is not a better test but no test, which is what this path had.
 *
 * <h2>The one that was not covered</h2>
 * <p>A context-less job is refused fail-closed, and {@code CommunityJobJfrTest} already covers that
 * for a <b>one-shot</b> trigger — the shape that retired correctly even before this was repaired,
 * because a one-shot retires whatever the outcome. A <b>repeating</b> trigger is the shape that did
 * not: the refusal left it runnable, so it came due again on the next interval and was refused again
 * for a reason that cannot change between intervals, since the context is captured once at
 * submission and never re-derived.
 */
@DisplayName("CommunityJobScheduler — what a settled job releases")
class CommunityJobLifecycleTest {

    private static final long TIMEOUT_SECONDS = 5L;
    private static final Duration INTERVAL = Duration.ofMinutes(1);
    /** Intervals stepped past a refusal — a job still due would fire this many more times. */
    private static final int EXTRA_INTERVALS = 3;
    /** Run count that proves a repeating job survived its first settle decision. */
    private static final int SECOND_RUN = 2;

    @Nested
    @DisplayName("A job that can never dispatch")
    class Unrunnable {

        @Test
        @DisplayName("a REPEATING context-less job retires instead of coming due again")
        void repeatingContextLessJobRetires() {
            VirtualSchedulerClock clock = new VirtualSchedulerClock();
            AtomicInteger bodyRuns = new AtomicInteger();

            try (JobScheduler scheduler = new CommunityJobScheduler(
                    new JobSchedulerConfig("lifecycle"), clock)) {
                // Submitted with no context bound at all — the fail-closed refusal (ADR-057 §5).
                CommunityJobHandle handle = (CommunityJobHandle) scheduler.submit(new JobDescriptor(
                        "unscoped-repeating",
                        new JobTrigger.FixedInterval(Duration.ZERO, INTERVAL),
                        bodyRuns::incrementAndGet));

                // Waiting on state() would race: finishRun() sets FAILED and only THEN computes the
                // next deadline, so a test that advanced the clock on seeing FAILED could sail past
                // the requeue and never observe it. Waiting for the release is waiting for the
                // settle itself to be complete, which is the fact under test.
                awaitReleased(handle);

                for (int i = 0; i < EXTRA_INTERVALS; i++) {
                    clock.advance(INTERVAL);
                }

                assertThat(handle.state())
                        .as("terminal after one refusal — a job whose context can never come back "
                            + "is not a job having a bad run")
                        .isEqualTo(JobState.FAILED);
                assertThat(bodyRuns)
                        .as("and the body never ran, refusal or not")
                        .hasValue(0);
            }
        }

        @Test
        @DisplayName("jobName() still answers after the descriptor is released")
        void jobNameSurvivesRelease() {
            VirtualSchedulerClock clock = new VirtualSchedulerClock();

            try (JobScheduler scheduler = new CommunityJobScheduler(
                    new JobSchedulerConfig("lifecycle"), clock)) {
                CommunityJobHandle handle = (CommunityJobHandle) scheduler.submit(new JobDescriptor(
                        "named", new JobTrigger.OneShot(Duration.ZERO), () -> { }));
                awaitReleased(handle);

                // jobName() used to read through the descriptor. Releasing it without copying the
                // name out turns every post-mortem lookup — the thing ADR-057 §6 keeps the handle
                // addressable FOR — into a NullPointerException.
                assertThat(handle.jobName()).isEqualTo("named");
            }
        }
    }

    @Nested
    @DisplayName("A job that finishes")
    class Finished {

        @Test
        @DisplayName("a completed one-shot drops its body and the submitter's identity")
        void completedOneShotReleasesPayload() {
            VirtualSchedulerClock clock = new VirtualSchedulerClock();
            CountDownLatch ran = new CountDownLatch(1);

            try (JobScheduler scheduler = new CommunityJobScheduler(
                    new JobSchedulerConfig("lifecycle"), clock)) {
                CommunityJobHandle handle = ScopedValue
                        .where(KernelProviders.STORAGE_CONTEXT,
                                ImmutableStorageContext.shared("tenant-alpha"))
                        .call(() -> (CommunityJobHandle) scheduler.submit(new JobDescriptor(
                                "reporting", new JobTrigger.OneShot(Duration.ZERO),
                                ran::countDown)));
                awaitQuietly(ran);
                awaitReleased(handle);

                assertThat(handle.context())
                        .as("the captured context carries the submitter's identity; a scheduler that "
                            + "kept one per job it has ever run pins an identity per job, forever")
                        .isNull();
            }
        }

        @Test
        @DisplayName("a repeating job keeps its body between runs")
        void repeatingJobKeepsPayloadBetweenRuns() {
            VirtualSchedulerClock clock = new VirtualSchedulerClock();
            AtomicInteger runs = new AtomicInteger();

            try (JobScheduler scheduler = new CommunityJobScheduler(
                    new JobSchedulerConfig("lifecycle"), clock)) {
                CommunityJobHandle handle = ScopedValue
                        .where(KernelProviders.STORAGE_CONTEXT,
                                ImmutableStorageContext.shared("tenant-alpha"))
                        .call(() -> (CommunityJobHandle) scheduler.submit(new JobDescriptor(
                                "heartbeat",
                                new JobTrigger.FixedInterval(Duration.ZERO, INTERVAL),
                                runs::incrementAndGet)));

                // The guard on the release: a settle that fired after every run would leave the
                // second dispatch with no body to call. Two runs is the smallest proof it does not.
                awaitRuns(runs, SECOND_RUN, clock);

                // At least, not exactly: the loop advances until the count moves, and a step can
                // carry the clock past more than one deadline. The claim is that a second run
                // happened at all, which is what a premature release would have made impossible.
                assertThat(runs.get()).isGreaterThanOrEqualTo(SECOND_RUN);
                assertThat(handle.descriptor()).as("still holds its body, having not settled").isNotNull();
            }
        }
    }

    @Nested
    @DisplayName("A job that is cancelled")
    class Cancelled {

        @Test
        @DisplayName("cancelling a queued job releases immediately")
        void cancellingQueuedJobReleases() {
            VirtualSchedulerClock clock = new VirtualSchedulerClock();

            try (JobScheduler scheduler = new CommunityJobScheduler(
                    new JobSchedulerConfig("lifecycle"), clock)) {
                CommunityJobHandle handle = ScopedValue
                        .where(KernelProviders.STORAGE_CONTEXT,
                                ImmutableStorageContext.shared("tenant-alpha"))
                        .call(() -> (CommunityJobHandle) scheduler.submit(new JobDescriptor(
                                "far-future",
                                new JobTrigger.OneShot(Duration.ofHours(1)),
                                () -> { })));

                assertThat(handle.cancel()).isTrue();
                assertThat(handle.descriptor())
                        .as("nothing is reading it — the job never started")
                        .isNull();
            }
        }

        @Test
        @DisplayName("cancelling a RUNNING job waits for the body before releasing")
        void cancellingRunningJobDefersRelease() throws InterruptedException {
            VirtualSchedulerClock clock = new VirtualSchedulerClock();
            CountDownLatch started = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);

            try (JobScheduler scheduler = new CommunityJobScheduler(
                    new JobSchedulerConfig("lifecycle"), clock)) {
                CommunityJobHandle handle = ScopedValue
                        .where(KernelProviders.STORAGE_CONTEXT,
                                ImmutableStorageContext.shared("tenant-alpha"))
                        .call(() -> (CommunityJobHandle) scheduler.submit(new JobDescriptor(
                                "in-flight", new JobTrigger.OneShot(Duration.ZERO), () -> {
                                    started.countDown();
                                    awaitQuietly(release);
                                })));
                assertThat(started.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();

                assertThat(handle.cancel()).isTrue();
                assertThat(handle.descriptor())
                        .as("the body is mid-flight and execute() is still reading this descriptor; "
                            + "releasing on the canceller's thread would null it under the runner")
                        .isNotNull();

                release.countDown();
                awaitReleased(handle);
            }
        }
    }

    // =========================================================================
    // Fixture
    // =========================================================================

    /**
     * Polls until the handle has dropped its payload.
     *
     * <p>Polling, not sleeping: it returns the moment the settle lands, and its bound is a failure
     * timeout rather than a delay every run pays. A handle that never releases fails the assertion
     * that follows rather than hanging the suite.
     */
    private static void awaitReleased(CommunityJobHandle handle) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(TIMEOUT_SECONDS);
        while (handle.descriptor() != null && System.nanoTime() < deadline) {
            Thread.onSpinWait();
        }
        assertThat(handle.descriptor())
                .as("a job that can no longer run must not keep the application's body alive; the "
                    + "handle stays addressable by id, what it points at does not")
                .isNull();
    }

    /**
     * Advances the virtual clock until the body has run {@code target} times.
     *
     * <p>Advancing a fixed number of intervals up front does not work: the next deadline is computed
     * from the clock as it stands when the previous run FINISHES, so every advance can land before
     * the requeue and leave the job due in a future the test already skipped past. Stepping until the
     * count moves is indifferent to where the requeue lands.
     */
    private static void awaitRuns(AtomicInteger runs, int target, VirtualSchedulerClock clock) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(TIMEOUT_SECONDS);
        while (runs.get() < target && System.nanoTime() < deadline) {
            clock.advance(INTERVAL);
            Thread.onSpinWait();
        }
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException _) {
            Thread.currentThread().interrupt();
        }
    }
}
