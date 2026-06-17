/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.community.transport;

import jdk.jfr.Category;
import jdk.jfr.Description;
import jdk.jfr.Event;
import jdk.jfr.FlightRecorder;
import jdk.jfr.Label;
import jdk.jfr.Name;
import jdk.jfr.StackTrace;

/**
 * PERF-RESEARCH instrumentation — invocation count and wall-time of {@code closeKeyStream}.
 *
 * <h2>Why this exists</h2>
 * <p>Under TLS h2, JFR hot-methods attributes ~15% of CPU to {@code NativeTcpCarrier.closeKeyStream}
 * reached via the {@code runLoop} {@code catch (RuntimeException)} branch — yet the recording shows
 * only ~200 thrown exceptions over the whole run. Those two facts cannot both be true unless either
 * (a) {@code closeKeyStream} is invoked far more often than the captured exception count (exception
 * events undercounted / a per-request close-churn path), or (b) each invocation is disproportionately
 * expensive, or (c) the leaf attribution is an artifact. This counter resolves it directly:
 * invocations × mean-time = real wall-time, compared against the 15% CPU claim.
 *
 * <p>{@code totalInvocations} counts every entry; {@code actualCloses} counts entries that resolved a
 * live stream and ran teardown (the rest are no-op early returns on an already-gone stream — a level-
 * triggered selector re-firing a closing key). Cumulative snapshots piggyback the ingress-drain emit
 * cadence and fire once on shutdown. <strong>Research-branch-only; not for merge.</strong>
 *
 * @since 0.9.0
 */
@Name("eu.exeris.kernel.transport.CommunityCloseKeyStream")
@Label("Community closeKeyStream Profile")
@Description("Invocation count and cumulative wall-time of closeKeyStream (PERF-RESEARCH)")
@Category({"Exeris Kernel", "Transport"})
@StackTrace(false)
final class CommunityCloseKeyStreamEvent extends Event {

    @Label("Total Invocations")
    /* default */ long totalInvocations;

    @Label("Actual Closes (live stream)")
    /* default */ long actualCloses;

    @Label("Cumulative Wall Nanos")
    /* default */ long totalNanos;

    @Label("Mean Micros Per Invocation")
    /* default */ double meanMicrosPerInvocation;

    @Label("Final Snapshot")
    /* default */ boolean finalSnapshot;

    /* default */ static void emit(long totalInvocations,
                                   long actualCloses,
                                   long totalNanos,
                                   double meanMicrosPerInvocation,
                                   boolean finalSnapshot) {
        if (!FlightRecorder.isInitialized()) {
            return;
        }
        CommunityCloseKeyStreamEvent event = new CommunityCloseKeyStreamEvent();
        if (event.isEnabled()) {
            event.totalInvocations = totalInvocations;
            event.actualCloses = actualCloses;
            event.totalNanos = totalNanos;
            event.meanMicrosPerInvocation = meanMicrosPerInvocation;
            event.finalSnapshot = finalSnapshot;
            event.commit();
        }
    }
}
