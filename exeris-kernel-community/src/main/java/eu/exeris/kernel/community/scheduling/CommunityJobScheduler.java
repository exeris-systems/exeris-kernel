/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.community.scheduling;

import eu.exeris.kernel.spi.exceptions.KernelErrorCodes;
import eu.exeris.kernel.spi.exceptions.scheduling.JobSchedulerException;
import eu.exeris.kernel.spi.scheduling.JobDescriptor;
import eu.exeris.kernel.spi.scheduling.JobHandle;
import eu.exeris.kernel.spi.scheduling.JobScheduler;
import eu.exeris.kernel.spi.scheduling.JobSchedulerConfig;
import eu.exeris.kernel.spi.scheduling.JobState;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Community {@link JobScheduler} (ADR-057): one dispatcher virtual thread, a delay-ordered set of
 * jobs, and an injected clock.
 *
 * <p>No {@code ScheduledExecutorService} — it computes deadlines from {@code System.nanoTime()} with
 * no seam to displace, which would put a deterministic trigger test out of reach (ADR-057 §3). No
 * {@code StructuredTaskScope} either: it is the kernel's last preview dependency, and a new subsystem
 * must not become a fifth taint site (§2). Containment comes from {@link JobHandle#cancel()} plus the
 * drain in {@link #close()} instead (§6).
 *
 * <p>This class owns the dispatch loop and the worker threads. What jobs exist and what state they
 * are in belongs to {@link CommunityJobRegistry}; when a trigger next fires, to
 * {@link CommunityTriggerClock}; whose identity a job runs under, to {@link CapturedContext}.
 *
 * @since 0.11.0
 */
public final class CommunityJobScheduler implements JobScheduler {

    private static final String NO_CONTEXT_REASON = "no-captured-context";

    private final String schedulerName;
    private final CommunitySchedulerClock clock;
    private final CommunityTriggerClock triggers;
    private final CommunityJobRegistry registry;
    private final List<Thread> inFlight = new ArrayList<>();
    private final AtomicLong sequence = new AtomicLong();

    private final Thread dispatcher;

    /* default */ CommunityJobScheduler(JobSchedulerConfig config, CommunitySchedulerClock clock) {
        this.schedulerName = Objects.requireNonNull(config, "config must not be null").schedulerName();
        this.clock = clock;
        this.triggers = new CommunityTriggerClock(clock);
        this.registry = new CommunityJobRegistry(clock, triggers);
        this.dispatcher = Thread.ofVirtual()
                .name("exeris-job-dispatcher-" + schedulerName)
                .start(this::dispatchLoop);
    }

    @Override
    public JobHandle submit(JobDescriptor descriptor) {
        Objects.requireNonNull(descriptor, "descriptor must not be null");
        // Captured at submission, rebound at dispatch (ADR-057 §5). A snapshot by design: a job
        // firing an hour later carries the authority that scheduled it.
        CapturedContext context = CapturedContext.capture();

        clock.lock().lock();
        try {
            if (!registry.isRunning()) {
                throw JobSchedulerException.schedulerClosed(schedulerName, descriptor.jobName());
            }
            String jobId = schedulerName + "-" + sequence.incrementAndGet();
            triggers.register(jobId, descriptor.trigger());
            CommunityJobHandle handle = new CommunityJobHandle(registry, jobId, descriptor, context,
                    triggers.firstDue(jobId, descriptor.trigger()));
            registry.register(handle);
            return handle;
        } finally {
            clock.lock().unlock();
        }
    }

    @Override
    public JobHandle handle(String jobId) {
        return registry.find(jobId);
    }

    @Override
    public void close() {
        List<Thread> pending;
        clock.lock().lock();
        try {
            if (!registry.isRunning()) {
                return;
            }
            registry.shutdown();
            pending = List.copyOf(inFlight);
        } finally {
            clock.lock().unlock();
        }
        dispatcher.interrupt();
        // Drained, not abandoned (ADR-057 §6): the containment this subsystem substitutes for a
        // structured scope is only real if shutdown actually waits.
        join(dispatcher);
        pending.forEach(CommunityJobScheduler::join);
    }

    private void dispatchLoop() {
        while (true) {
            CommunityJobHandle due;
            clock.lock().lock();
            try {
                if (!registry.isRunning()) {
                    return;
                }
                CommunityJobHandle earliest = registry.earliest();
                if (earliest == null) {
                    clock.awaitSignal();
                    continue;
                }
                if (clock.nanoTime() < earliest.dueNanos()) {
                    clock.awaitUntil(earliest.dueNanos());
                    continue;
                }
                due = earliest;
                // Out of the queue for the whole run and back only when it finishes, so a job is
                // either queued or running and never both. Rescheduling here instead would let a body
                // that overruns its interval start again before the first run returned — fixed-rate
                // behaviour, which FixedInterval explicitly does not promise.
                registry.checkOut(due);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } finally {
                clock.lock().unlock();
            }
            dispatch(due);
        }
    }

    private void dispatch(CommunityJobHandle handle) {
        Thread worker = Thread.ofVirtual()
                .name("exeris-job-" + handle.jobId())
                .unstarted(() -> runJob(handle));
        clock.lock().lock();
        try {
            inFlight.add(worker);
        } finally {
            clock.lock().unlock();
        }
        worker.start();
    }

    private void runJob(CommunityJobHandle handle) {
        JobState outcome = JobState.COMPLETED;
        try {
            if (handle.context().isEmpty()) {
                // Fail-closed (ADR-057 §5): running under an ambient identity is how scheduled work
                // acquires authority nobody granted it.
                CommunityJobJfrEvents.emitFailure(schedulerName, handle.jobName(), handle.jobId(),
                        KernelErrorCodes.EX_JOB_9001, NO_CONTEXT_REASON);
                outcome = JobState.FAILED;
                return;
            }
            outcome = execute(handle);
        } finally {
            retire(handle, outcome);
        }
    }

    private JobState execute(CommunityJobHandle handle) {
        CommunityJobJfrEvents.emitDispatch(schedulerName, handle.jobName(), handle.jobId());
        long startNanos = clock.nanoTime();
        try {
            handle.context().run(handle.descriptor().body());
            // A job body is arbitrary application code and may throw anything; letting it escape
            // would kill the worker and lose the failure with it.
        } catch (RuntimeException | Error e) { //NOPMD AvoidCatchingGenericException
            CommunityJobJfrEvents.emitFailure(schedulerName, handle.jobName(), handle.jobId(),
                    KernelErrorCodes.EX_JOB_9003, e.getClass().getName());
            return JobState.FAILED;
        }
        CommunityJobJfrEvents.emitCompletion(
                schedulerName, handle.jobName(), handle.jobId(), clock.nanoTime() - startNanos);
        return JobState.COMPLETED;
    }

    private void retire(CommunityJobHandle handle, JobState outcome) {
        clock.lock().lock();
        try {
            inFlight.remove(Thread.currentThread());
        } finally {
            clock.lock().unlock();
        }
        registry.finishRun(handle, outcome);
    }

    private static void join(Thread thread) {
        try {
            thread.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
