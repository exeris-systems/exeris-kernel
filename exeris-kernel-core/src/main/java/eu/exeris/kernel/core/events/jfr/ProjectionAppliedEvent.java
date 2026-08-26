/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.core.events.jfr;

import jdk.jfr.Category;
import jdk.jfr.Event;
import jdk.jfr.Label;
import jdk.jfr.StackTrace;

/**
 * JFR event emitted when a Projection handler processes an event.
 *
 * @since 0.5.0
 */
@Label("Projection Applied")
@Category({"Exeris", "Events", "Projection"})
@StackTrace(false)
public final class ProjectionAppliedEvent extends Event {

    @Label("Projection Name")
    public String projectionName;

    @Label("Event Type Ordinal")
    public int eventTypeOrdinal;

    @Label("Stream ID High")
    public long streamIdHigh;

    @Label("Stream ID Low")
    public long streamIdLow;
}
