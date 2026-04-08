/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
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
