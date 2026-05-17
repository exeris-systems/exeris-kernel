/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.community.events.jfr;

import jdk.jfr.Category;
import jdk.jfr.Description;
import jdk.jfr.Event;
import jdk.jfr.Label;
import jdk.jfr.Name;
import jdk.jfr.StackTrace;

/**
 * Emitted when {@code CommunityEventQueue} refuses a push because the bus is in
 * fail-fast mode and the queue is at capacity (EVENT-111, v0.8 Sprint 5).
 *
 * <p>Operators rely on this event to attribute publish overflow rates to
 * specific event types and to track backpressure trends over time — the
 * publishing-caller {@code EventBusException} surface is per-call and leaves
 * no post-mortem trail.
 *
 * <p>Mirrors the established overflow-telemetry pattern:
 * {@code AsyncTelemetryDropEvent} (telemetry sink),
 * {@code TransportIngressQueueDepthEvent} (transport ingress),
 * {@code OutboxDlqEvent} (DLQ transitions). All four can be aggregated into
 * a single operator dashboard for system-wide backpressure visibility.
 */
@Name("eu.exeris.kernel.events.CommunityEventQueueOverflow")
@Label("Community Event Queue Overflow")
@Category({"Exeris Kernel", "Events"})
@Description("Emitted when CommunityEventQueue.push refuses a fail-fast publish because the "
        + "queue is at capacity. Carries engine name, event type, observed depth, and capacity "
        + "so operators can attribute overflow rates to specific publishers + event types and "
        + "track backpressure trends over time.")
@StackTrace(false)
public final class CommunityEventQueueOverflowEvent extends Event {

    @Label("Engine Name")
    @Description("Human-readable engine name from EventEngineConfig.engineName()")
    /* default */ String engineName;

    @Label("Event Type")
    @Description("Event type name from the registry (registry.nameOfOrdinal(...)); "
            + "blank when the ordinal is unregistered")
    /* default */ String eventType;

    @Label("Queue Depth")
    @Description("Queue size observed at the moment of overflow (equal to capacity by definition)")
    /* default */ int queueDepth;

    @Label("Queue Capacity")
    @Description("Configured queue capacity from EventEngineConfig.queueCapacity()")
    /* default */ int queueCapacity;

    public static void emit(String engineName,
                            String eventType,
                            int queueDepth,
                            int queueCapacity) {
        CommunityEventQueueOverflowEvent event = new CommunityEventQueueOverflowEvent();
        if (!event.isEnabled()) {
            return;
        }
        event.begin();
        event.engineName = engineName;
        event.eventType = eventType == null ? "" : eventType;
        event.queueDepth = queueDepth;
        event.queueCapacity = queueCapacity;
        event.commit();
    }
}
