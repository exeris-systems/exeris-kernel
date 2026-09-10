/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.scheduling;

import eu.exeris.kernel.spi.context.KernelProviders;
import eu.exeris.kernel.spi.exceptions.KernelErrorCodes;
import eu.exeris.kernel.spi.scheduling.JobDescriptor;
import eu.exeris.kernel.spi.scheduling.JobHandle;
import eu.exeris.kernel.spi.scheduling.JobScheduler;
import eu.exeris.kernel.spi.scheduling.JobSchedulerConfig;
import eu.exeris.kernel.spi.scheduling.JobState;
import eu.exeris.kernel.spi.scheduling.JobTrigger;
import eu.exeris.kernel.spi.security.ImmutableStorageContext;
import jdk.jfr.Recording;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordingFile;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The scheduler's JFR surface (ADR-057 §8).
 *
 * <p>The fail-closed case is the one that needs a JFR assertion rather than a contract assertion.
 * At the SPI level a refused dispatch and a crashed one look identical — the body did not run and the
 * state is {@code FAILED}. Only the recorded error code distinguishes "the kernel refused this" from
 * "something went wrong", and an operator needs that difference.
 */
@DisplayName("CommunityJobScheduler — JFR dispatch, completion, and failure events")
class CommunityJobJfrTest {

    private static final String DISPATCH = "eu.exeris.kernel.scheduling.JobDispatch";
    private static final String COMPLETION = "eu.exeris.kernel.scheduling.JobCompletion";
    private static final String FAILURE = "eu.exeris.kernel.scheduling.JobFailure";

    private static final long TIMEOUT_SECONDS = 5L;

    @Test
    @DisplayName("a normal run commits dispatch and completion")
    void emitsDispatchAndCompletion(@TempDir Path tmp) throws Exception {
        VirtualSchedulerClock clock = new VirtualSchedulerClock();
        CountDownLatch fired = new CountDownLatch(1);

        List<RecordedEvent> events = recordEvents(tmp, clock, recorder -> {
            try (JobScheduler scheduler = new CommunityJobScheduler(
                    new JobSchedulerConfig("jfr"), clock)) {
                ScopedValue.where(KernelProviders.STORAGE_CONTEXT,
                                ImmutableStorageContext.shared("tenant-alpha"))
                        .run(() -> scheduler.submit(new JobDescriptor(
                                "reporting", new JobTrigger.OneShot(Duration.ZERO),
                                fired::countDown)));
                awaitQuietly(fired);
            }
        });

        RecordedEvent dispatch = firstOf(events, DISPATCH);
        assertThat(dispatch.getString("schedulerName")).isEqualTo("jfr");
        assertThat(dispatch.getString("jobName")).isEqualTo("reporting");

        RecordedEvent completion = firstOf(events, COMPLETION);
        assertThat(completion.getString("jobName")).isEqualTo("reporting");
        assertThat(completion.getLong("durationNanos")).isNotNegative();
    }

    @Test
    @DisplayName("a context-less job records the fail-closed refusal, not a generic failure")
    void emitsFailClosedRefusal(@TempDir Path tmp) throws Exception {
        VirtualSchedulerClock clock = new VirtualSchedulerClock();

        List<RecordedEvent> events = recordEvents(tmp, clock, recorder -> {
            try (JobScheduler scheduler = new CommunityJobScheduler(
                    new JobSchedulerConfig("jfr"), clock)) {
                awaitFailed(scheduler.submit(new JobDescriptor(
                        "unscoped", new JobTrigger.OneShot(Duration.ZERO), () -> { })));
            }
        });

        RecordedEvent failure = firstOf(events, FAILURE);
        assertThat(failure.getString("errorCode"))
                .as("ADR-057 §5 — a refusal and a crash are indistinguishable at the SPI level; the "
                        + "error code is what tells an operator the kernel decided this")
                .isEqualTo(KernelErrorCodes.EX_JOB_9001);
        assertThat(failure.getString("reason")).isEqualTo("no-captured-context");
        assertThat(events).noneSatisfy(event ->
                assertThat(event.getEventType().getName()).isEqualTo(DISPATCH));
    }

    @Test
    @DisplayName("a throwing body records the body's exception class as the reason")
    void emitsBodyFailure(@TempDir Path tmp) throws Exception {
        VirtualSchedulerClock clock = new VirtualSchedulerClock();
        CountDownLatch fired = new CountDownLatch(1);

        List<RecordedEvent> events = recordEvents(tmp, clock, recorder -> {
            try (JobScheduler scheduler = new CommunityJobScheduler(
                    new JobSchedulerConfig("jfr"), clock)) {
                ScopedValue.where(KernelProviders.STORAGE_CONTEXT,
                                ImmutableStorageContext.shared("tenant-alpha"))
                        .run(() -> scheduler.submit(new JobDescriptor(
                                "throwing", new JobTrigger.OneShot(Duration.ZERO), () -> {
                                    fired.countDown();
                                    throw new IllegalStateException("failed on purpose");
                                })));
                awaitQuietly(fired);
            }
        });

        RecordedEvent failure = firstOf(events, FAILURE);
        assertThat(failure.getString("errorCode")).isEqualTo(KernelErrorCodes.EX_JOB_9003);
        assertThat(failure.getString("reason"))
                .isEqualTo(IllegalStateException.class.getName());
    }

    private static List<RecordedEvent> recordEvents(Path tmp, VirtualSchedulerClock clock,
                                                    Body body) throws Exception {
        Path jfr = tmp.resolve("scheduling.jfr");
        try (Recording recording = new Recording()) {
            recording.enable(DISPATCH);
            recording.enable(COMPLETION);
            recording.enable(FAILURE);
            recording.start();
            body.run(clock);
            recording.stop();
            recording.dump(jfr);
        }
        return RecordingFile.readAllEvents(jfr);
    }

    private static RecordedEvent firstOf(List<RecordedEvent> events, String name) {
        return events.stream()
                .filter(e -> name.equals(e.getEventType().getName()))
                .findFirst().orElseThrow();
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException _) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * The refusal path has no latch to wait on — the body never runs by design — so the handle's
     * state is the signal. Polling it beats sleeping: it returns as soon as the refusal is recorded
     * and its bound is a failure timeout rather than a fixed delay every run pays.
     */
    private static void awaitFailed(JobHandle handle) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(TIMEOUT_SECONDS);
        while (handle.state() != JobState.FAILED && System.nanoTime() < deadline) {
            Thread.onSpinWait();
        }
    }

    @FunctionalInterface
    private interface Body {
        void run(VirtualSchedulerClock clock) throws Exception;
    }
}
