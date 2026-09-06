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
 * JFR event emitted by {@link eu.exeris.kernel.core.events.InMemoryEventBus} on every
 * {@code publish()} and {@code publishAndAwait()} call, immediately after subscribers are
 * resolved and before any of them is dispatched to.
 *
 * <p>{@code @StackTrace(false)} — zero-overhead telemetry per the Performance Contract.
 *
 * @since 0.5
 */
@Label("Event Bus Publish")
@Category({"Exeris", "Events", "Bus"})
@StackTrace(false)
public final class EventBusPublishEvent extends Event {

    /** {@link eu.exeris.kernel.spi.events.EventDescriptor#eventTypeOrdinal()} of the published event. */
    @Label("Event Type Ordinal")
    public int eventTypeOrdinal;

    /**
     * Number of subscribers resolved for this event's ordinal at publish time, before dispatch.
     * {@code 0} means the payload was closed immediately with no handler invoked.
     */
    @Label("Handler Count")
    public int handlerCount;

    /**
     * {@code true} for {@code publishAndAwait} — handlers run sequentially on the calling
     * thread, which returns once every handler has completed; {@code false} for {@code publish}
     * — handlers run fire-and-forget, one virtual thread per handler, and the calling thread
     * returns immediately without waiting.
     */
    @Label("Await Mode")
    public boolean awaitMode;
}
