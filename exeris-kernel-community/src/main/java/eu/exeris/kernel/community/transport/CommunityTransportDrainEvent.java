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
 * JFR event recording the outcome of the graceful-stop drain.
 *
 * <h2>JFR-First Contract</h2>
 * <p>Emitted once per {@code stop()}, on the stopping thread, after the drain has either completed or
 * hit its deadline. It exists because the two outcomes are operationally different and otherwise
 * indistinguishable: a drain that completes means every admitted request got its response, while a
 * drain that times out means the kernel severed live exchanges. Today only a log line marks the
 * second case, and the first leaves no trace at all — so an operator tuning
 * {@code terminationGracePeriodSeconds} has nothing to tune against.
 *
 * <p>{@code streamsRemaining > 0} is the signal that matters: the deadline fired first. Pair it with
 * {@code durationNanos} to tell "handlers are genuinely slow" from "one handler is stuck".
 *
 * <p>Counts only — no peer identity, no request content. Single-phase {@code commit()} on the platform
 * thread driving shutdown; never on a virtual thread, so the carrier-pinning hazard that afflicts
 * begin/commit straddles does not arise.
 *
 * @since 0.11.0
 */
@Name("eu.exeris.kernel.transport.CommunityTransportDrain")
@Label("Community Transport Drain")
@Description("Outcome of the graceful-stop in-flight stream drain: completed, or cut short by deadline")
@Category({"Exeris Kernel", "Transport"})
@StackTrace(false)
final class CommunityTransportDrainEvent extends Event {

    @Label("Engine Name")
    /* default */ String engineName;

    @Label("Streams In Flight At Drain Start")
    /* default */ int streamsAtStart;

    @Label("Streams Remaining After Drain")
    /* default */ int streamsRemaining;

    @Label("Drain Duration (ns)")
    /* default */ long durationNanos;

    /* default */ static void emit(String engineName,
                                   int streamsAtStart,
                                   int streamsRemaining,
                                   long durationNanos) {
        if (!FlightRecorder.isInitialized()) {
            return;
        }
        CommunityTransportDrainEvent event = new CommunityTransportDrainEvent();
        if (event.isEnabled()) {
            event.engineName = engineName;
            event.streamsAtStart = streamsAtStart;
            event.streamsRemaining = streamsRemaining;
            event.durationNanos = durationNanos;
            event.commit();
        }
    }
}
