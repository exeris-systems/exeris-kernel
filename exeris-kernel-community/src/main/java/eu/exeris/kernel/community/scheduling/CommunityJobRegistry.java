/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.community.scheduling;

import eu.exeris.kernel.spi.scheduling.JobState;
import eu.exeris.kernel.spi.scheduling.JobTrigger;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * What jobs exist and what state they are in — all of it under the clock's lock.
 *
 * <p>Separated from {@link CommunityJobScheduler}, which owns the dispatch loop and the worker
 * threads. Keeping every guarded mutation in one class is what makes the locking auditable: there is
 * a single place to check that nothing touches the queue or a job's state outside the lock.
 *
 * @since 0.11.0
 */
final class CommunityJobRegistry {

    private final CommunitySchedulerClock clock;
    private final Map<String, CommunityJobHandle> jobs = new ConcurrentHashMap<>();
    private final List<CommunityJobHandle> queue = new ArrayList<>();
    private final CommunityTriggerClock triggers;

    private boolean running = true;

    /* default */ CommunityJobRegistry(CommunitySchedulerClock clock, CommunityTriggerClock triggers) {
        this.clock = clock;
        this.triggers = triggers;
    }

    /** Caller holds the lock. */
    /* default */ boolean isRunning() {
        return running;
    }

    /** Caller holds the lock. Registers a job and wakes the dispatcher. */
    /* default */ void register(CommunityJobHandle handle) {
        jobs.put(handle.jobId(), handle);
        queue.add(handle);
        clock.signal();
    }

    /** Takes the lock itself — for callers that are not already inside a critical section. */
    /* default */ boolean isRunningNow() {
        clock.lock().lock();
        try {
            return running;
        } finally {
            clock.lock().unlock();
        }
    }

    /* default */ CommunityJobHandle find(String jobId) {
        return jobs.get(jobId);
    }

    /** Caller holds the lock. The next job due, or {@code null} when nothing is scheduled. */
    /* default */ CommunityJobHandle earliest() {
        CommunityJobHandle best = null;
        for (CommunityJobHandle candidate : queue) {
            if (best == null || candidate.dueNanos() < best.dueNanos()) {
                best = candidate;
            }
        }
        return best;
    }

    /** Caller holds the lock. Takes a job out of the queue for the duration of a run. */
    /* default */ void checkOut(CommunityJobHandle handle) {
        queue.remove(handle);
        handle.rawState(JobState.RUNNING);
    }

    /* default */ JobState stateOf(CommunityJobHandle handle) {
        clock.lock().lock();
        try {
            return handle.rawState();
        } finally {
            clock.lock().unlock();
        }
    }

    /* default */ boolean cancel(CommunityJobHandle handle) {
        clock.lock().lock();
        try {
            if (handle.isTerminal() || handle.rawState() == JobState.CANCELLED) {
                return false;
            }
            handle.rawState(JobState.CANCELLED);
            queue.remove(handle);
            clock.signal();
            return true;
        } finally {
            clock.lock().unlock();
        }
    }

    /**
     * Decides what happens after a run: a one-shot settles terminally, a repeating job goes back in
     * the queue with a fresh deadline.
     *
     * <p>A failed run still reschedules — one failure is not evidence the schedule is wrong — but the
     * state stays {@code FAILED} until the next dispatch, so the last outcome remains observable. A
     * job cancelled mid-run, or a scheduler closed under it, does not reschedule.
     */
    /* default */ void finishRun(CommunityJobHandle handle, JobState outcome) {
        clock.lock().lock();
        try {
            if (handle.rawState() == JobState.CANCELLED) {
                return;
            }
            if (!running || handle.descriptor().trigger() instanceof JobTrigger.OneShot) {
                handle.rawState(outcome);
                return;
            }
            // SCHEDULED, not the outcome: a requeued job reporting COMPLETED would look terminal to
            // cancel(), which would then refuse and leave it queued — cancellation silently doing
            // nothing. FAILED is kept, so the last outcome stays observable.
            handle.rawState(outcome == JobState.FAILED ? JobState.FAILED : JobState.SCHEDULED);
            handle.dueNanos(triggers.nextDue(handle.jobId(), handle.descriptor().trigger()));
            queue.add(handle);
            clock.signal();
        } finally {
            clock.lock().unlock();
        }
    }

    /** Caller holds the lock. Stops accepting work and settles everything still queued. */
    /* default */ void shutdown() {
        running = false;
        for (CommunityJobHandle handle : queue) {
            if (!handle.isTerminal()) {
                handle.rawState(JobState.COMPLETED);
            }
        }
        queue.clear();
        clock.signal();
    }
}
