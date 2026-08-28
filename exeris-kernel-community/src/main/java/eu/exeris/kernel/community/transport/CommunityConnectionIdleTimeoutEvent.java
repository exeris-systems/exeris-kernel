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
 * JFR event for a connection reclaimed by {@code transport.idleTimeoutMillis}.
 *
 * <h2>JFR-First Contract</h2>
 * <p>Emitted once per reclaimed connection, from {@code NativeTcpIdleReaper.sweep} on the owning
 * reactor's thread, immediately before the abortive teardown. It is the only signal that the
 * timeout did anything: reclamation is invisible to the application (no handler runs) and
 * indistinguishable at the peer from any other reset, so without this event an operator who
 * lowers the timeout has no way to tell whether it took effect or whether the traffic simply
 * changed — the failure this knob spent two releases in, being carried and enforced by nothing.
 *
 * <p>Both the observed idle span and the configured timeout are carried, because the interesting
 * quantity is their ratio: a fleet whose idle spans cluster just past the limit is being trimmed
 * on a threshold, while one whose spans are minutes past it is reclaiming genuinely dead peers.
 * No threshold is baked in — what counts as too aggressive is a deployment's judgement.
 *
 * <p>Single-phase {@code commit()}; zero overhead when JFR is not recording. Not on the
 * read/write hot path — the sweep that emits it runs at most once per sweep interval.
 *
 * @since 0.12.0
 */
@Name("eu.exeris.kernel.transport.CommunityConnectionIdleTimeout")
@Label("Community Connection Idle Timeout")
@Description("Connection reclaimed after moving no bytes for the configured transport idle timeout")
@Category({"Exeris Kernel", "Transport"})
@StackTrace(false)
final class CommunityConnectionIdleTimeoutEvent extends Event {

    @Label("Stream Id")
    /* default */ long streamId;

    @Label("Reactor Index")
    /* default */ int reactorIndex;

    @Label("Idle Millis")
    /* default */ long idleMillis;

    @Label("Configured Timeout Millis")
    /* default */ long configuredTimeoutMillis;

    /* default */ static void emit(long streamId,
                                   int reactorIndex,
                                   long idleMillis,
                                   long configuredTimeoutMillis) {
        if (!FlightRecorder.isInitialized()) {
            return;
        }
        CommunityConnectionIdleTimeoutEvent event = new CommunityConnectionIdleTimeoutEvent();
        if (event.isEnabled()) {
            event.streamId = streamId;
            event.reactorIndex = reactorIndex;
            event.idleMillis = idleMillis;
            event.configuredTimeoutMillis = configuredTimeoutMillis;
            event.commit();
        }
    }
}
