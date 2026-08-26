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
 * JFR event for a connection refused at accept time because the carrier is at
 * {@code TransportConfig.maxConnections()}.
 *
 * <h2>Why this exists</h2>
 * <p>The refusal closes a socket the kernel has already accepted at TCP level. From the client the
 * connection opens and dies; from the server, before this event, nothing happened at all — no log
 * line, no event, and {@code TransportStats.totalRejected} counted only PAQS load-sheds, so the one
 * field an operator would consult read zero while every connection was being turned away. Telemetry
 * that stays silent during a total outage is worse than absent telemetry, because it is read as
 * evidence the fault is elsewhere.
 *
 * <h2>Volume</h2>
 * <p>One event per refused connection, matching {@code StreamShedEvent}'s per-shed shape. The path
 * only executes when the carrier is already at its ceiling, so the event rate is bounded by the
 * client's retry rate rather than by served traffic — and a burst of these is precisely the signal
 * an operator needs, not noise to be aggregated away.
 *
 * <p>Carries no peer identity: the refusal happens before the remote address is resolved, so there
 * is nothing to record and nothing to leak.
 *
 * <p>Single-phase {@code commit()}, zero overhead when JFR is not recording.
 *
 * @since 0.11.0
 */
@Name("eu.exeris.kernel.transport.CommunityConnectionRefused")
@Label("Community Connection Refused")
@Description("A connection was accepted at TCP level and then closed because the carrier had reached "
        + "its configured maxConnections ceiling")
@Category({"Exeris Kernel", "Transport"})
@StackTrace(false)
final class CommunityConnectionRefusedEvent extends Event {

    @Label("Bind Address")
    /* default */ String bindAddress;

    @Label("Port")
    /* default */ int port;

    @Label("Active Connections")
    /* default */ long activeConnections;

    @Label("Configured Max Connections")
    /* default */ int maxConnections;

    @Label("Total Refused")
    @Description("Cumulative refusals on this carrier since start — a single event says little, the "
            + "slope says whether this is a blip or a wall")
    /* default */ long totalRefused;

    /* default */ static void emit(String bindAddress, int port, long activeConnections,
                                   int maxConnections, long totalRefused) {
        if (!FlightRecorder.isInitialized()) {
            return;
        }
        CommunityConnectionRefusedEvent event = new CommunityConnectionRefusedEvent();
        if (event.isEnabled()) {
            event.bindAddress = bindAddress;
            event.port = port;
            event.activeConnections = activeConnections;
            event.maxConnections = maxConnections;
            event.totalRefused = totalRefused;
            event.commit();
        }
    }
}
