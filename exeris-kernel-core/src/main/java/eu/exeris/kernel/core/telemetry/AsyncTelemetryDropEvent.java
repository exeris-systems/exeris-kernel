/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.core.telemetry;

import jdk.jfr.Category;
import jdk.jfr.Description;
import jdk.jfr.Event;
import jdk.jfr.Label;
import jdk.jfr.Name;
import jdk.jfr.StackTrace;

/**
 * JFR event emitted whenever {@link AsyncTelemetrySink} drops an event because its
 * bounded ring is full. Operators rely on this signal to size the ring appropriately
 * and to detect runaway emission rates that overwhelm the consumer.
 *
 * <h2>Allocation discipline</h2>
 * <p>{@link StackTrace} is disabled — the drop path is the worst possible time to
 * walk a stack. The event captures only the constants needed for diagnosis.
 */
@Name("eu.exeris.kernel.telemetry.AsyncTelemetryDrop")
@Label("Async Telemetry Drop")
@Category({"Exeris Kernel", "Telemetry"})
@Description("Emitted when AsyncTelemetrySink drops an event because the bounded ring is full.")
@StackTrace(false)
@SuppressWarnings("unused") // fields are read by the JFR runtime via reflection
final class AsyncTelemetryDropEvent extends Event {

    @Label("Sink Name")
    /* default */ String sinkName;

    @Label("Event Code")
    /* default */ String eventCode;

    @Label("Total Drops")
    /* default */ long totalDrops;

    @Label("Ring Capacity")
    /* default */ int ringCapacity;

    private AsyncTelemetryDropEvent() {
        super();
    }

    /* default */ static void emit(String sinkName, String eventCode, long totalDrops, int ringCapacity) {
        AsyncTelemetryDropEvent event = new AsyncTelemetryDropEvent();
        if (!event.isEnabled()) {
            return;
        }
        event.sinkName = sinkName;
        event.eventCode = eventCode;
        event.totalDrops = totalDrops;
        event.ringCapacity = ringCapacity;
        event.commit();
    }
}
