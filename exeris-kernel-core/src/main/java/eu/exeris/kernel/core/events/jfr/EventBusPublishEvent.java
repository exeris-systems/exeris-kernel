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
 * JFR event emitted on each {@code EventBus.publish()} invocation.
 *
 * <p>{@code @StackTrace(false)} — zero-overhead telemetry per the Performance Contract.
 *
 * @since 0.5.0
 */
@Label("Event Bus Publish")
@Category({"Exeris", "Events", "Bus"})
@StackTrace(false)
public final class EventBusPublishEvent extends Event {

    @Label("Event Type Ordinal")
    public int eventTypeOrdinal;

    @Label("Handler Count")
    public int handlerCount;

    @Label("Await Mode")
    public boolean awaitMode;
}
