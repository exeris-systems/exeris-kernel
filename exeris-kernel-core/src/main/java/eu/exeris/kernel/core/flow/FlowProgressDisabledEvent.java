/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.core.flow;

import jdk.jfr.Category;
import jdk.jfr.Description;
import jdk.jfr.Event;
import jdk.jfr.Label;
import jdk.jfr.Name;
import jdk.jfr.StackTrace;

@Name("eu.exeris.kernel.flow.ProgressDisabled")
@Label("Flow Progress Publication Disabled")
@Category({"Exeris Kernel", "Flow"})
@Description("Emitted once per engine when FlowProgress cannot claim an event ordinal and "
        + "progress publication is disabled for the life of the process. The publisher probes a "
        + "bounded window of hash-derived candidates; when every one collides with a registered "
        + "type it gives up permanently. Nothing else records that: publishProgress then returns "
        + "on a cached sentinel, silently, and a consumer subscribed to FlowProgress simply never "
        + "receives anything - which is indistinguishable from a system where no flow ever "
        + "terminated.")
@StackTrace(false)
final class FlowProgressDisabledEvent extends Event {

    @Label("Event Type Name")
    @Description("The event type whose ordinal could not be claimed")
    /* default */ String eventTypeName;

    @Label("Base Ordinal")
    @Description("First candidate probed; the window runs upward from here")
    /* default */ int baseOrdinal;

    @Label("Probe Limit")
    @Description("Number of consecutive candidates tried before giving up")
    /* default */ int probeLimit;

    /* default */ static void emit(String eventTypeName, int baseOrdinal, int probeLimit) {
        FlowProgressDisabledEvent event = new FlowProgressDisabledEvent();
        if (!event.isEnabled()) {
            return;
        }
        event.eventTypeName = eventTypeName;
        event.baseOrdinal = baseOrdinal;
        event.probeLimit = probeLimit;
        event.commit();
    }
}
