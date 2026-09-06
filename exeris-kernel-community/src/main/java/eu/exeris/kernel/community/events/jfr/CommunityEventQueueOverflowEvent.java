/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
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
 * fail-fast mode and the queue is at capacity.
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
 *
 * @implNote {@link #emit} commits in two phases — {@link Event#begin()}, then only local field
 *           assignment, then {@link Event#commit()} — so the timed interval never straddles a
 *           blocking operation on the emitting thread.
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

    /**
     * Commits this event, recording the engine name, event type, and the queue depth/capacity
     * observed at the moment of the refused push.
     *
     * <p>A no-op when the event is disabled.
     *
     * @param engineName    human-readable engine name from {@code EventEngineConfig.engineName()}
     * @param eventType     event type name; recorded as empty when {@code null}
     * @param queueDepth    queue size observed at the moment of overflow
     * @param queueCapacity configured queue capacity
     */
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
