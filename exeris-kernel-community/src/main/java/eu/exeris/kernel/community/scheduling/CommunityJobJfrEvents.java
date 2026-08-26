/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.scheduling;

import jdk.jfr.Category;
import jdk.jfr.Description;
import jdk.jfr.Event;
import jdk.jfr.FlightRecorder;
import jdk.jfr.Label;
import jdk.jfr.Name;
import jdk.jfr.StackTrace;
import jdk.jfr.Timespan;

/**
 * JFR events for the Community job scheduler (ADR-057 §8).
 *
 * <h2>Single-phase commit — contractual here, not advisory</h2>
 * <p>Every event is populated and committed in one shot. A scheduler's entire purpose is dispatching
 * blocking work onto virtual threads, so it is the subsystem most exposed to the
 * {@code begin() → blocking-op → commit()} straddle that binds a carrier-local {@code EventWriter}
 * across a park. Durations are measured with plain nanos and attached at commit instead.
 *
 * <h2>Secret-safe</h2>
 * <p>Job names are developer-chosen identifiers. Nothing a job body touched, and no principal or
 * tenant identifier, is recorded.
 *
 * @since 0.11.0
 */
final class CommunityJobJfrEvents {

    private CommunityJobJfrEvents() {
        // Utility holder — not instantiable.
    }

    /* default */ static void emitDispatch(String schedulerName, String jobName, String jobId) {
        if (!FlightRecorder.isInitialized()) {
            return;
        }
        JobDispatchEvent event = new JobDispatchEvent();
        if (event.isEnabled()) {
            event.schedulerName = schedulerName;
            event.jobName = jobName;
            event.jobId = jobId;
            event.commit();
        }
    }

    /* default */ static void emitCompletion(String schedulerName, String jobName, String jobId,
                                             long durationNanos) {
        if (!FlightRecorder.isInitialized()) {
            return;
        }
        JobCompletionEvent event = new JobCompletionEvent();
        if (event.isEnabled()) {
            event.schedulerName = schedulerName;
            event.jobName = jobName;
            event.jobId = jobId;
            event.durationNanos = durationNanos;
            event.commit();
        }
    }

    /* default */ static void emitFailure(String schedulerName, String jobName, String jobId,
                                          String errorCode, String reason) {
        if (!FlightRecorder.isInitialized()) {
            return;
        }
        JobFailureEvent event = new JobFailureEvent();
        if (event.isEnabled()) {
            event.schedulerName = schedulerName;
            event.jobName = jobName;
            event.jobId = jobId;
            event.errorCode = errorCode;
            event.reason = reason;
            event.commit();
        }
    }

    @Name("eu.exeris.kernel.scheduling.JobDispatch")
    @Label("Job Dispatch")
    @Description("Emitted when the scheduler dispatches a job onto a virtual thread")
    @Category({"Exeris Kernel", "Scheduling"})
    @StackTrace(false)
    /* default */ static final class JobDispatchEvent extends Event {

        @Label("Scheduler Name")
        /* default */ String schedulerName;

        @Label("Job Name")
        /* default */ String jobName;

        @Label("Job ID")
        /* default */ String jobId;
    }

    @Name("eu.exeris.kernel.scheduling.JobCompletion")
    @Label("Job Completion")
    @Description("Emitted when a dispatched job body returns normally")
    @Category({"Exeris Kernel", "Scheduling"})
    @StackTrace(false)
    /* default */ static final class JobCompletionEvent extends Event {

        @Label("Scheduler Name")
        /* default */ String schedulerName;

        @Label("Job Name")
        /* default */ String jobName;

        @Label("Job ID")
        /* default */ String jobId;

        @Label("Execution Duration")
        @Timespan(Timespan.NANOSECONDS)
        /* default */ long durationNanos;
    }

    @Name("eu.exeris.kernel.scheduling.JobFailure")
    @Label("Job Failure")
    @Description("Emitted when a job body throws, or when a dispatch is refused because the "
            + "submission captured no identity context (ADR-057 §5)")
    @Category({"Exeris Kernel", "Scheduling"})
    @StackTrace(false)
    /* default */ static final class JobFailureEvent extends Event {

        @Label("Scheduler Name")
        /* default */ String schedulerName;

        @Label("Job Name")
        /* default */ String jobName;

        @Label("Job ID")
        /* default */ String jobId;

        @Label("Error Code")
        /* default */ String errorCode;

        @Label("Reason")
        /* default */ String reason;
    }
}
