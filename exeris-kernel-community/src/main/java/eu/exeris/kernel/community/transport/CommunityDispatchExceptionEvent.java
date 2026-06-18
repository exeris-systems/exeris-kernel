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
 * PERF-RESEARCH instrumentation — type breakdown of the RuntimeExceptions caught in the reactor
 * select loop and funnelled into {@code closeKeyStream}.
 *
 * <h2>Why this exists</h2>
 * <p>The {@code CommunityCloseKeyStream} counter proved {@code closeKeyStream} fires ~1796x/request
 * (~908M total in a 120s TLS h2 run), all via the single {@code NativeTcpReactor} {@code
 * catch (RuntimeException)} branch — a teardown busy-spin on zombie connections. That tells us the
 * <em>spin</em> exists but not <em>what throws</em>: JFR {@code jdk.JavaExceptionThrow} was
 * undersampled (the prior "~200 exceptions" was an artifact). This counter names the trigger directly
 * by classifying every caught exception by its type and keeping one sample message per type, so the
 * abortive-teardown fix can be aimed precisely instead of blindly.
 *
 * <p>{@code breakdown} is {@code type:count;type:count} ordered by descending count; {@code
 * dominantSample} is the first-seen {@code getMessage()} of the most frequent type (truncated).
 * Cumulative snapshots piggyback the ingress-drain emit cadence and fire once on shutdown.
 * <strong>Research-branch-only; not for merge.</strong>
 *
 * @since 0.9.0
 */
@Name("eu.exeris.kernel.transport.CommunityDispatchException")
@Label("Community Reactor Dispatch Exception Breakdown")
@Description("Type breakdown of RuntimeExceptions caught in the reactor select loop (PERF-RESEARCH)")
@Category({"Exeris Kernel", "Transport"})
@StackTrace(false)
final class CommunityDispatchExceptionEvent extends Event {

    @Label("Total Exceptions Caught")
    /* default */ long totalExceptions;

    @Label("Distinct Types")
    /* default */ int distinctTypes;

    @Label("Breakdown (type:count; ...)")
    /* default */ String breakdown;

    @Label("Dominant Type Sample Message")
    /* default */ String dominantSample;

    @Label("Final Snapshot")
    /* default */ boolean finalSnapshot;

    /* default */ static void emit(long totalExceptions,
                                   int distinctTypes,
                                   String breakdown,
                                   String dominantSample,
                                   boolean finalSnapshot) {
        if (!FlightRecorder.isInitialized()) {
            return;
        }
        CommunityDispatchExceptionEvent event = new CommunityDispatchExceptionEvent();
        if (event.isEnabled()) {
            event.totalExceptions = totalExceptions;
            event.distinctTypes = distinctTypes;
            event.breakdown = breakdown;
            event.dominantSample = dominantSample;
            event.finalSnapshot = finalSnapshot;
            event.commit();
        }
    }
}
