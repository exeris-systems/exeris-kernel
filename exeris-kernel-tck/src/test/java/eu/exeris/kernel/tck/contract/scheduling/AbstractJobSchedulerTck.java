/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.tck.contract.scheduling;

import eu.exeris.kernel.spi.context.KernelProviders;
import eu.exeris.kernel.spi.exceptions.KernelErrorCodes;
import eu.exeris.kernel.spi.exceptions.scheduling.JobSchedulerException;
import eu.exeris.kernel.spi.scheduling.JobDescriptor;
import eu.exeris.kernel.spi.scheduling.JobHandle;
import eu.exeris.kernel.spi.scheduling.JobScheduler;
import eu.exeris.kernel.spi.scheduling.JobState;
import eu.exeris.kernel.spi.scheduling.JobTrigger;
import eu.exeris.kernel.spi.security.ImmutableStorageContext;
import eu.exeris.kernel.spi.security.StorageContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

/**
 * Contract for {@link JobScheduler} (ADR-057).
 *
 * <h2>Time is advanced, never slept through</h2>
 * <p>Every timing assertion moves virtual time via {@link #advanceTime(Duration)}. The latch waits
 * that follow are failure bounds, not schedule waits — they block only until the dispatcher has
 * actually run the job, which is immediate once its deadline passes. A trigger suite that slept would
 * be slow and flaky in the same measure, which is the whole reason ADR-057 §3-4 refuses
 * {@code ScheduledExecutorService} and injects the wait primitive.
 *
 * <h2>Bindings must not weaken this</h2>
 * <p>{@link #advanceTime(Duration)} is abstract rather than defaulted-to-sleep. A default would let a
 * binding pass the suite while sitting on real time, which is exactly the coverage this contract
 * exists to prevent.
 *
 * @since 0.11.0
 */
public abstract class AbstractJobSchedulerTck {

    /** Generous: it bounds a scheduling failure, never a schedule. */
    private static final long FIRE_TIMEOUT_SECONDS = 5L;

    /** Short: it must be long enough to catch a wrong early fire, short enough to run often. */
    private static final long SILENCE_MILLIS = 250L;

    private static final String TENANT = "tenant-alpha";

    /** Interval for the refusal cases; any value works, since time is advanced rather than waited. */
    private static final Duration REFUSAL_INTERVAL = Duration.ofMinutes(1);

    /** Intervals stepped past a refusal — a job still due would come round this many more times. */
    private static final int REFUSAL_INTERVALS = 3;

    /** A second close() must return promptly; the first one is what drains. */
    private static final int SECOND_CLOSE_TIMEOUT_SECONDS = 5;

    /** Attempts for the shutdown-race case; see {@link #assertDrainIsTotal} for what that buys. */
    private static final int DRAIN_ATTEMPTS = 20;

    /** How long a raced body runs, so an unwaited-for one is still going when close() returns. */
    private static final long BODY_MILLIS = 5L;

    private JobScheduler scheduler;

    /**
     * Creates a scheduler bound to the binding's controllable clock.
     *
     * @return a started scheduler
     */
    protected abstract JobScheduler createScheduler();

    /**
     * Moves the binding's clock forward and releases anything parked on it.
     *
     * @param amount how far to advance both the monotonic and calendar clocks
     */
    protected abstract void advanceTime(Duration amount);

    @BeforeEach
    void openScheduler() {
        scheduler = createScheduler();
    }

    @AfterEach
    void closeScheduler() {
        scheduler.close();
    }

    // =========================================================================
    // Triggers
    // =========================================================================

    @Nested
    @DisplayName("Triggers fire when their schedule says, and not before")
    class Triggers {

        @Test
        @DisplayName("a one-shot fires once its delay elapses")
        void oneShotFires() throws InterruptedException {
            CountDownLatch fired = new CountDownLatch(1);
            submitAsTenant("one-shot", new JobTrigger.OneShot(Duration.ofMinutes(10)),
                    fired::countDown);

            advanceTime(Duration.ofMinutes(10));

            assertThat(fired.await(FIRE_TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();
        }

        @Test
        @DisplayName("a one-shot stays silent until its delay elapses")
        void oneShotDoesNotFireEarly() throws InterruptedException {
            CountDownLatch fired = new CountDownLatch(1);
            submitAsTenant("early", new JobTrigger.OneShot(Duration.ofMinutes(10)),
                    fired::countDown);

            advanceTime(Duration.ofMinutes(9));

            assertThat(fired.await(SILENCE_MILLIS, TimeUnit.MILLISECONDS))
                    .as("a trigger that fires early is worse than one that fires late — it runs work "
                            + "under preconditions the caller has not established yet")
                    .isFalse();
        }

        @Test
        @DisplayName("a one-shot fires exactly once, even after further time passes")
        void oneShotFiresOnce() throws InterruptedException {
            AtomicInteger runs = new AtomicInteger();
            CountDownLatch fired = new CountDownLatch(1);
            submitAsTenant("once", new JobTrigger.OneShot(Duration.ofMinutes(1)), () -> {
                runs.incrementAndGet();
                fired.countDown();
            });

            advanceTime(Duration.ofMinutes(1));
            assertThat(fired.await(FIRE_TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();
            advanceTime(Duration.ofHours(1));

            assertStaysAt(runs, 1);
        }

        @Test
        @DisplayName("a fixed-interval job waits its initial delay, then repeats")
        void fixedIntervalRepeats() throws InterruptedException {
            CountDownLatch fired = new CountDownLatch(3);
            JobHandle handle = submitAsTenant("repeating",
                    new JobTrigger.FixedInterval(Duration.ofMinutes(1), Duration.ofMinutes(5)),
                    fired::countDown);

            advanceTime(Duration.ofMinutes(1));
            awaitCount(fired, 2);
            awaitSettled(handle);
            advanceTime(Duration.ofMinutes(5));
            awaitCount(fired, 1);
            awaitSettled(handle);
            advanceTime(Duration.ofMinutes(5));

            assertThat(fired.await(FIRE_TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();
        }

        @Test
        @DisplayName("a cron job fires at its next matching minute")
        void cronFires() throws InterruptedException {
            CountDownLatch fired = new CountDownLatch(1);
            // Every minute: the next match is never more than a minute away, whatever instant the
            // binding's clock starts from, so the case does not depend on a fixed epoch.
            submitAsTenant("cron", new JobTrigger.Cron("* * * * *"), fired::countDown);

            advanceTime(Duration.ofMinutes(1));

            assertThat(fired.await(FIRE_TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();
        }

        @Test
        @DisplayName("a cron job whose minute has not arrived stays silent")
        void cronDoesNotFireEarly() throws InterruptedException {
            CountDownLatch fired = new CountDownLatch(1);
            // Once a day at 03:00 — a schedule no plausible test clock is about to reach.
            submitAsTenant("nightly", new JobTrigger.Cron("0 3 * * *"), fired::countDown);

            advanceTime(Duration.ofMinutes(1));

            assertThat(fired.await(SILENCE_MILLIS, TimeUnit.MILLISECONDS)).isFalse();
        }
    }

    // =========================================================================
    // Cancellation and shutdown
    // =========================================================================

    @Nested
    @DisplayName("Cancellation and shutdown bound every dispatch")
    class Lifecycle {

        @Test
        @DisplayName("a cancelled job never fires")
        void cancelPreventsDispatch() throws InterruptedException {
            CountDownLatch fired = new CountDownLatch(1);
            JobHandle handle = submitAsTenant("cancelled",
                    new JobTrigger.OneShot(Duration.ofMinutes(10)), fired::countDown);

            assertThat(handle.cancel()).isTrue();
            advanceTime(Duration.ofHours(1));

            assertThat(fired.await(SILENCE_MILLIS, TimeUnit.MILLISECONDS)).isFalse();
            assertThat(handle.state()).isEqualTo(JobState.CANCELLED);
        }

        @Test
        @DisplayName("cancelling twice reports the second call as a no-op")
        void cancelIsIdempotent() {
            JobHandle handle = submitAsTenant("twice",
                    new JobTrigger.OneShot(Duration.ofMinutes(10)), () -> { });

            assertThat(handle.cancel()).isTrue();
            assertThat(handle.cancel())
                    .as("idempotent, and the return value says which call did the work")
                    .isFalse();
        }

        @Test
        @DisplayName("a cancelled repeating job stops repeating")
        void cancelStopsRepetition() throws InterruptedException {
            AtomicInteger runs = new AtomicInteger();
            CountDownLatch fired = new CountDownLatch(1);
            JobHandle handle = submitAsTenant("stoppable",
                    new JobTrigger.FixedInterval(Duration.ZERO, Duration.ofMinutes(1)), () -> {
                        runs.incrementAndGet();
                        fired.countDown();
                    });

            assertThat(fired.await(FIRE_TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();
            handle.cancel();
            int afterCancel = runs.get();
            advanceTime(Duration.ofHours(1));

            assertStaysAt(runs, afterCancel);
        }

        @Test
        @DisplayName("a submitted job is reachable by id")
        void handleIsAddressable() {
            JobHandle handle = submitAsTenant("addressable",
                    new JobTrigger.OneShot(Duration.ofMinutes(10)), () -> { });

            assertThat(scheduler.handle(handle.jobId()))
                    .as("ADR-057 §6 — a dispatch no handle can reach would break the lifecycle "
                            + "boundary that stands in for a structured scope")
                    .isSameAs(handle);
        }

        @Test
        @DisplayName("submitting to a closed scheduler is refused")
        void submitAfterCloseIsRefused() {
            scheduler.close();

            assertThatThrownBy(() -> scheduler.submit(new JobDescriptor(
                    "late", new JobTrigger.OneShot(Duration.ZERO), () -> { })))
                    .isInstanceOfSatisfying(JobSchedulerException.class, ex ->
                            assertThat(ex.errorCode()).isEqualTo(KernelErrorCodes.EX_JOB_9002));
        }

        @Test
        @DisplayName("close is idempotent — and the second call is not a second drain")
        void closeIsIdempotent() throws InterruptedException {
            // A scheduler with nothing in it drains instantly, so timing a second close against an
            // empty one proves nothing. Give the first close real work to drain, so the second is
            // the only call that could still be waiting on anything.
            CountDownLatch started = new CountDownLatch(1);
            submitAsTenant("draining", new JobTrigger.OneShot(Duration.ZERO), () -> {
                started.countDown();
                sleepQuietly(BODY_MILLIS);
            });
            assertThat(started.await(FIRE_TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();

            scheduler.close();

            // The failure this bounds is a drain loop whose exit condition can only be satisfied
            // once — a terminal seal, a queue already emptied. The second call cannot satisfy it, so
            // it waits out its whole deadline before giving up, and a shutdown that nests a
            // try-with-resources inside an explicit close pays that in full.
            assertTimeoutPreemptively(Duration.ofSeconds(SECOND_CLOSE_TIMEOUT_SECONDS),
                    () -> scheduler.close(),
                    "the second close() did not return promptly — it is repeating the drain");

            assertThatThrownBy(() -> scheduler.submit(new JobDescriptor(
                    "after-double-close", new JobTrigger.OneShot(Duration.ZERO), () -> { })))
                    .as("and the scheduler is still closed afterwards: idempotent means the second "
                        + "call changes nothing, not that it reopens anything")
                    .isInstanceOf(JobSchedulerException.class);
        }

        @Test
        @DisplayName("close() does not return while a body it started is still running")
        void closeDrainIsTotalUnderRace() throws InterruptedException {
            for (int attempt = 0; attempt < DRAIN_ATTEMPTS; attempt++) {
                assertDrainIsTotal(createScheduler());
            }
        }

        @Test
        @DisplayName("close waits for a run already in flight")
        void closeDrainsInFlight() throws InterruptedException {
            CountDownLatch started = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            AtomicReference<Boolean> finished = new AtomicReference<>(false);

            submitAsTenant("draining", new JobTrigger.OneShot(Duration.ZERO), () -> {
                started.countDown();
                awaitQuietly(release);
                finished.set(true);
            });

            assertThat(started.await(FIRE_TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();
            release.countDown();
            scheduler.close();

            assertThat(finished.get())
                    .as("ADR-057 §6 — abandoning a run at shutdown leaves the containment "
                            + "unenforced at the one moment it is load-bearing")
                    .isTrue();
        }
    }

    // =========================================================================
    // Identity
    // =========================================================================

    @Nested
    @DisplayName("Identity crosses the job boundary by capture")
    class Identity {

        @Test
        @DisplayName("the submitting context is rebound on the dispatching thread")
        void contextIsRebound() throws InterruptedException {
            CountDownLatch fired = new CountDownLatch(1);
            AtomicReference<String> seen = new AtomicReference<>();

            submitAsTenant("scoped", new JobTrigger.OneShot(Duration.ZERO), () -> {
                seen.set(KernelProviders.STORAGE_CONTEXT.get().isolationKey().orElse(null));
                fired.countDown();
            });

            assertThat(fired.await(FIRE_TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();
            assertThat(seen.get())
                    .as("ADR-057 §5 — a job runs under the identity that scheduled it")
                    .isEqualTo(TENANT);
        }

        @Test
        @DisplayName("the dispatching thread does not inherit the submitter's context ambiently")
        void contextDoesNotLeakOutsideTheJob() throws InterruptedException {
            CountDownLatch fired = new CountDownLatch(1);
            submitAsTenant("scoped", new JobTrigger.OneShot(Duration.ZERO), fired::countDown);
            assertThat(fired.await(FIRE_TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();

            assertThat(KernelProviders.STORAGE_CONTEXT.isBound())
                    .as("the rebind is scoped to the dispatch, not to the scheduler")
                    .isFalse();
        }

        @Test
        @DisplayName("a job submitted with no context refuses to run")
        void contextlessJobFailsClosed() throws InterruptedException {
            CountDownLatch fired = new CountDownLatch(1);
            JobHandle handle = scheduler.submit(new JobDescriptor(
                    "unscoped", new JobTrigger.OneShot(Duration.ZERO), fired::countDown));

            assertThat(fired.await(SILENCE_MILLIS, TimeUnit.MILLISECONDS))
                    .as("ADR-057 §5 — fail closed; running under an ambient or default identity is "
                            + "how scheduled work acquires authority nobody granted it")
                    .isFalse();
            assertThat(handle.state()).isEqualTo(JobState.FAILED);
        }

        /**
         * The repeating half of the same refusal, and the half a provider can get wrong on its own.
         *
         * <p>Context is captured once at submission, so a refusal for want of it is a permanent
         * property of the job rather than a bad run. A provider that settles the refusal as an
         * ordinary failure re-queues the job, re-refuses on the next interval, and does so forever:
         * a failure event per interval on work that can never dispatch, and a handle that never
         * reaches a terminal state. Nothing the body can observe distinguishes the two — the body
         * never runs either way — so the contract is expressed where it is visible, on the state.
         */
        @Test
        @DisplayName("a repeating job refused for want of context retires, instead of coming due every interval")
        void contextlessRepeatingJobRetires() {
            AtomicInteger runs = new AtomicInteger();
            JobHandle handle = scheduler.submit(new JobDescriptor(
                    "unscoped-repeating",
                    new JobTrigger.FixedInterval(Duration.ZERO, REFUSAL_INTERVAL),
                    runs::incrementAndGet));

            awaitState(handle, JobState.FAILED);

            // Advanced repeatedly rather than once: a provider may set the state before it computes
            // the next deadline, so a single advance can slip through the window between the two and
            // never witness the re-queue it is looking for.
            for (int interval = 0; interval < REFUSAL_INTERVALS; interval++) {
                advanceTime(REFUSAL_INTERVAL);
                assertStaysAt(handle, JobState.FAILED);
            }

            assertThat(runs)
                    .as("and the body never ran — the refusal is the point, not a failed attempt")
                    .hasValue(0);
        }
    }

    // =========================================================================
    // Failure
    // =========================================================================

    @Nested
    @DisplayName("A failing job is recorded, not fatal")
    class Failure {

        @Test
        @DisplayName("a throwing body marks the job failed")
        void throwingBodyMarksFailed() throws InterruptedException {
            CountDownLatch fired = new CountDownLatch(1);
            JobHandle handle = submitAsTenant("throwing",
                    new JobTrigger.OneShot(Duration.ZERO), () -> {
                        fired.countDown();
                        throw new IllegalStateException("job body failed on purpose");
                    });

            assertThat(fired.await(FIRE_TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();
            awaitState(handle, JobState.FAILED);

            assertThat(handle.state()).isEqualTo(JobState.FAILED);
        }

        @Test
        @DisplayName("a throwing run does not stop the schedule or the scheduler")
        void throwingBodyKeepsScheduleAlive() throws InterruptedException {
            CountDownLatch fired = new CountDownLatch(2);
            JobHandle handle = submitAsTenant("flaky",
                    new JobTrigger.FixedInterval(Duration.ZERO, Duration.ofMinutes(1)), () -> {
                        fired.countDown();
                        throw new IllegalStateException("job body failed on purpose");
                    });

            awaitCount(fired, 1);
            // Advancing before the run has been rescheduled would move the clock past a deadline that
            // does not exist yet, and the next one would be computed from the advanced time.
            awaitSettled(handle);
            advanceTime(Duration.ofMinutes(1));

            assertThat(fired.await(FIRE_TIMEOUT_SECONDS, TimeUnit.SECONDS))
                    .as("one failed run is not evidence the schedule is wrong")
                    .isTrue();
        }
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private JobHandle submitAsTenant(String jobName, JobTrigger trigger, Runnable body) {
        return submitTo(scheduler, jobName, trigger, body);
    }

    private static JobHandle submitTo(JobScheduler target, String jobName, JobTrigger trigger,
                                      Runnable body) {
        StorageContext context = ImmutableStorageContext.shared(TENANT);
        return ScopedValue.where(KernelProviders.STORAGE_CONTEXT, context)
                .call(() -> target.submit(new JobDescriptor(jobName, trigger, body)));
    }

    /**
     * Races a time advance against {@code close()} and asserts the drain was total.
     *
     * <p>The property is not "no body ran" — a body that overlaps shutdown is legitimate, and the
     * drain is supposed to wait for it. The property is that {@code close()} does not <em>return</em>
     * while a body it started is still unfinished. That is what "drained, not abandoned" means, and
     * it is checkable the instant close returns.
     *
     * <p><strong>What this does not cover.</strong> The motivating defect was a window between
     * checking a job out of the queue and registering its worker: a driver doing those in two
     * critical sections leaves the job in neither, and a close landing between them snapshots past it
     * and returns without waiting. That window is a few instructions wide, and reintroducing it does
     * <em>not</em> fail this case — the interleaving needed to hit it is not reachable by racing two
     * threads from outside. It is closed by construction instead, by doing both under one lock. This
     * case is a regression net for the coarser property, not a reproduction of that race, and it is
     * documented as such so nobody later reads a green run as proof the window is guarded.
     */
    private void assertDrainIsTotal(JobScheduler racing) throws InterruptedException {
        AtomicBoolean bodyStarted = new AtomicBoolean();
        AtomicBoolean bodyFinished = new AtomicBoolean();
        try {
            submitTo(racing, "racer", new JobTrigger.OneShot(Duration.ofMinutes(1)), () -> {
                bodyStarted.set(true);
                // Long enough that an unwaited-for body is still running when close() returns.
                long until = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(BODY_MILLIS);
                while (System.nanoTime() < until) {
                    Thread.onSpinWait();
                }
                bodyFinished.set(true);
            });
            Thread mover = Thread.ofVirtual().start(() -> advanceTime(Duration.ofMinutes(1)));
            racing.close();
            boolean abandoned = bodyStarted.get() && !bodyFinished.get();
            mover.join();

            assertThat(abandoned)
                    .as("ADR-057 §6 — close() returned while a body it had started was still "
                            + "running; the lifecycle boundary that stands in for a structured scope "
                            + "only holds if the drain is total")
                    .isFalse();
        } finally {
            racing.close();
        }
    }

    /** Waits until the latch has counted down to {@code remaining}, or fails the bound. */
    private static void awaitCount(CountDownLatch latch, long remaining) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(FIRE_TIMEOUT_SECONDS);
        while (latch.getCount() > remaining && System.nanoTime() < deadline) {
            Thread.onSpinWait();
        }
        assertThat(latch.getCount()).isLessThanOrEqualTo(remaining);
    }

    /**
     * Asserts a counter holds its value for the silence window. A single read would pass even if the
     * dispatcher were about to fire again, which is the failure this guards.
     */
    /**
     * Asserts a handle holds {@code expected} for the silence window.
     *
     * <p>The counterpart of {@link #assertStaysAt(AtomicInteger, int)} for a state rather than a
     * count, and it samples for the same reason: a job that has been wrongly re-queued spends almost
     * all of its time waiting for the next interval, so a single sample taken at the wrong moment
     * reads the terminal state the contract asks for.
     */
    private static void assertStaysAt(JobHandle handle, JobState expected) {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(SILENCE_MILLIS);
        while (System.nanoTime() < deadline) {
            assertThat(handle.state())
                    .as("a job that can never dispatch must stop being due")
                    .isEqualTo(expected);
            Thread.onSpinWait();
        }
    }

    private static void assertStaysAt(AtomicInteger counter, int expected) {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(SILENCE_MILLIS);
        while (System.nanoTime() < deadline) {
            assertThat(counter.get()).isEqualTo(expected);
            Thread.onSpinWait();
        }
    }

    /** Waits until a run has finished and the job has been rescheduled or settled. */
    private static void awaitSettled(JobHandle handle) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(FIRE_TIMEOUT_SECONDS);
        while (handle.state() == JobState.RUNNING && System.nanoTime() < deadline) {
            Thread.onSpinWait();
        }
    }

    private static void awaitState(JobHandle handle, JobState expected) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(FIRE_TIMEOUT_SECONDS);
        while (handle.state() != expected && System.nanoTime() < deadline) {
            Thread.onSpinWait();
        }
    }

    /** Occupies the body long enough that an unwaited-for run is still going when close() returns. */
    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException _) {
            Thread.currentThread().interrupt();
        }
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await(FIRE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException _) {
            Thread.currentThread().interrupt();
        }
    }
}
