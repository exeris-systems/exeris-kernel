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
 * <p>Emitted by {@code Projection.onEvent} after the user-supplied handler has successfully
 * folded the event into the projection's state and that state has been published — one commit
 * per event successfully applied. A handler that throws instead produces a
 * {@link ProjectionHandlerFailureEvent}, never this event.
 *
 * @since 0.5
 */
@Label("Projection Applied")
@Category({"Exeris", "Events", "Projection"})
@StackTrace(false)
public final class ProjectionAppliedEvent extends Event {

    /** Human-readable projection name, as supplied when the projection was constructed. */
    @Label("Projection Name")
    public String projectionName;

    /** {@code EventRegistry} ordinal of the event type that was applied. */
    @Label("Event Type Ordinal")
    public int eventTypeOrdinal;

    /** High 64 bits of the applied event's aggregate stream UUID. */
    @Label("Stream ID High")
    public long streamIdHigh;

    /** Low 64 bits of the applied event's aggregate stream UUID. */
    @Label("Stream ID Low")
    public long streamIdLow;
/**
 * Creates an unrecorded event.
 *
 * <p>The emitter assigns the public fields and calls {@link Event#commit()}. An instance that is never
 * committed contributes nothing to a recording.
 */
public ProjectionAppliedEvent() {
    // Declared, not added: the implicit no-arg constructor, written out so it can carry a comment.
    super();
}

}
