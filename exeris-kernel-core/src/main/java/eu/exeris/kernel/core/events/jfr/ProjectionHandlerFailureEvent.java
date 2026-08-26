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
 * JFR event emitted when a projection handler throws a {@link RuntimeException} during state fold,
 * causing the projection to potentially diverge from the event stream.
 */
@Label("Projection Handler Failure")
@Category({"Exeris", "Events", "Projection"})
@StackTrace(false)
public final class ProjectionHandlerFailureEvent extends Event {

    @Label("Projection Name")
    public String projectionName;

    @Label("Event Type Ordinal")
    public int eventTypeOrdinal;

    @Label("Exception Type")
    public String exceptionType;
}
