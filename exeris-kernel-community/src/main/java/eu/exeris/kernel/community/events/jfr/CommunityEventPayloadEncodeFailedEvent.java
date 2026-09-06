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
 * Emitted when {@code CommunityJsonEventPayloadCodec.encode} fails to serialize a
 * domain-event payload (ADR-046). The codec wraps the binding exception into a
 * {@code java.*} {@link RuntimeException} before it crosses the SPI boundary; this
 * event gives operators a post-mortem trail the per-call exception does not.
 *
 * <p><b>Secret-safe.</b> Carries only the payload runtime class, the requested
 * content-type, the event-type name, and the failing exception's class name — never
 * the payload bytes or any field values (the failure message is deliberately
 * omitted, as it may echo payload content).
 *
 * <p>Follows the events-package telemetry pattern
 * ({@link CommunityEventQueueOverflowEvent}): {@code @StackTrace(false)}, guarded by
 * {@link Event#isEnabled()} so a disabled recording costs ~zero.
 *
 * @implNote {@link #emit} commits in two phases — {@link Event#begin()}, then only local field
 *           assignment, then {@link Event#commit()} — so the timed interval never straddles a
 *           blocking operation on the emitting thread.
 */
@Name("eu.exeris.kernel.events.CommunityEventPayloadEncodeFailed")
@Label("Community Event Payload Encode Failed")
@Category({"Exeris Kernel", "Events"})
@Description("Emitted when the Community JSON event-payload codec fails to serialize a payload. "
        + "Carries the payload type, requested content-type, event-type name, and the failing "
        + "exception class — never payload bytes or field values (secret-safe).")
@StackTrace(false)
public final class CommunityEventPayloadEncodeFailedEvent extends Event {

    @Label("Payload Type")
    @Description("Runtime class of the payload object that failed to encode")
    /* default */ String payloadType;

    @Label("Content Type")
    @Description("Requested wire content-type from the codec context (e.g. application/json)")
    /* default */ String contentType;

    @Label("Event Type")
    @Description("Event-type name from the codec context; blank when not supplied")
    /* default */ String eventType;

    @Label("Failure Class")
    @Description("Class name of the binding exception that caused the failure "
            + "(e.g. a tools.jackson.* type); no message is recorded (secret-safe)")
    /* default */ String failureClass;

    /**
     * Commits this event, recording only the failing payload's runtime class, the requested
     * content-type, the event-type name, and the failure's class name.
     *
     * <p>A no-op when the event is disabled — the fields are never touched and no JFR call
     * beyond the initial {@link Event#isEnabled()} check happens.
     *
     * @param payloadType runtime class name of the payload that failed to encode
     * @param contentType requested content-type; recorded as empty when {@code null}
     * @param eventType   event-type name from the codec context; recorded as empty when
     *                    {@code null}
     * @param failure     the binding exception that caused the failure; only its class name is
     *                    recorded, never {@link Throwable#getMessage()}
     */
    public static void emit(String payloadType,
                            String contentType,
                            String eventType,
                            Throwable failure) {
        CommunityEventPayloadEncodeFailedEvent event = new CommunityEventPayloadEncodeFailedEvent();
        if (!event.isEnabled()) {
            return;
        }
        event.begin();
        event.payloadType = payloadType;
        event.contentType = contentType == null ? "" : contentType;
        event.eventType = eventType == null ? "" : eventType;
        // Class name only — never failure.getMessage(), which may echo payload field values.
        event.failureClass = failure == null ? "" : failure.getClass().getName();
        event.commit();
    }
}
