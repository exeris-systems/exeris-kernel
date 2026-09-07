/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
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
 * <p>{@code busyRemaining > 0} is the signal that matters: the deadline fired first. Pair it with
 * {@code durationNanos} to tell "handlers are genuinely slow" from "one handler is stuck". The gap
 * between {@code openAtStart} and {@code busyAtStart} is the idle connections that used to hold
 * shutdown open before 0.11.
 *
 * <p>Counts only — no peer identity, no request content. Single-phase {@code commit()} on the platform
 * thread driving shutdown; never on a virtual thread, so the carrier-pinning hazard that afflicts
 * begin/commit straddles does not arise.
 *
 * @since 0.11
 */
@Name("eu.exeris.kernel.transport.CommunityTransportDrain")
@Label("Community Transport Drain")
@Description("Outcome of the graceful-stop in-flight stream drain: completed, or cut short by deadline")
@Category({"Exeris Kernel", "Transport"})
@StackTrace(false)
final class CommunityTransportDrainEvent extends Event {

    @Label("Engine Name")
    /* default */ String engineName;

    @Label("Busy Streams At Drain Start")
    @Description("Streams actively being served when the drain began — what the drain waits on")
    /* default */ int busyAtStart;

    @Label("Busy Streams Remaining After Drain")
    @Description("Non-zero means the deadline fired while work was still in flight")
    /* default */ int busyRemaining;

    @Label("Open Streams At Drain Start")
    @Description("Connections admitted when the drain began, busy or idle. Reported for context: "
            + "before 0.11 the drain waited on THIS number, which an idle keep-alive connection "
            + "never lowers, so shutdown burned its full deadline")
    /* default */ int openAtStart;

    @Label("Drain Duration (ns)")
    /* default */ long durationNanos;

    /* default */ static void emit(String engineName,
                                   int busyAtStart,
                                   int busyRemaining,
                                   int openAtStart,
                                   long durationNanos) {
        if (!FlightRecorder.isInitialized()) {
            return;
        }
        CommunityTransportDrainEvent event = new CommunityTransportDrainEvent();
        if (event.isEnabled()) {
            event.engineName = engineName;
            event.busyAtStart = busyAtStart;
            event.busyRemaining = busyRemaining;
            event.openAtStart = openAtStart;
            event.durationNanos = durationNanos;
            event.commit();
        }
    }
}
