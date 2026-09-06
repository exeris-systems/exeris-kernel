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
 *
 * <p>Emitted by {@code Projection.onEvent}, from the {@code catch} around the handler invocation,
 * before the state fold's {@code RuntimeException} is rethrown to the event bus's dispatch path.
 * The projection's state is left unchanged for this event — {@link ProjectionAppliedEvent} is not
 * also emitted for the same event.
 */
@Label("Projection Handler Failure")
@Category({"Exeris", "Events", "Projection"})
@StackTrace(false)
public final class ProjectionHandlerFailureEvent extends Event {

    /** Human-readable projection name, as supplied when the projection was constructed. */
    @Label("Projection Name")
    public String projectionName;

    /** {@code EventRegistry} ordinal of the event type being applied when the handler threw. */
    @Label("Event Type Ordinal")
    public int eventTypeOrdinal;

    /**
     * Simple (not fully-qualified) class name of the {@link RuntimeException} the handler threw.
     */
    @Label("Exception Type")
    public String exceptionType;
}
