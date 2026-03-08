/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.spi.exceptions.events;

import eu.exeris.kernel.spi.events.EventBus;
import eu.exeris.kernel.spi.exceptions.ExerisKernelException;
import eu.exeris.kernel.spi.exceptions.KernelErrorCodes;

/**
 * Thrown when an {@link EventBus} operation fails (publish overflow, subscription error).
 *
 * <h2>Hierarchy &amp; java:S110</h2>
 * <p>Extends {@link ExerisKernelException} directly — one level below
 * {@code RuntimeException} in the Kernel tree — to stay within the
 * {@code java:S110} inheritance-depth limit of 5:
 * {@code Object → Throwable → Exception → RuntimeException →
 * ExerisKernelException → EventBusException}.
 *
 * <h2>rawArgs Binary Layout (Enterprise Glass-Box)</h2>
 * <p>For {@value KernelErrorCodes#EX_EVENT_6002} (queue overflow):
 * <ul>
 *   <li>index 0 – {@code String} eventType</li>
 *   <li>index 1 – {@code long}   queueDepth</li>
 *   <li>index 2 – {@code long}   queueCapacity</li>
 * </ul>
 *
 * @since 0.5.0
 */
public class EventBusException extends ExerisKernelException {

    private static final String MSG_PUBLISH_OVERFLOW = "Event bus queue overflow";

    /**
     * General-purpose constructor — use typed factory methods when possible.
     *
     * @param message static message template
     */
    public EventBusException(String message) {
        super(KernelErrorCodes.EX_EVENT_6002, message, (Throwable) null);
    }

    /**
     * General-purpose constructor with cause.
     *
     * @param message static message template
     * @param cause   upstream throwable; may be {@code null}
     */
    public EventBusException(String message, Throwable cause) {
        super(KernelErrorCodes.EX_EVENT_6002, message, cause);
    }

    // Full-args constructor for factory methods — must precede static factory methods per DeclarationOrder
    private EventBusException(String errorCode, String message, Throwable cause, Object... rawArgs) {
        super(errorCode, message, cause, rawArgs);
    }

    /**
     * Creates an {@code EventBusException} for a queue overflow on publish.
     *
     * <p>Sets error code {@value KernelErrorCodes#EX_EVENT_6002}.
     * rawArgs layout: {@code [String eventType, long queueDepth, long queueCapacity]}.
     *
     * @param eventType     the event type name that could not be published
     * @param queueDepth    current queue depth when the overflow occurred
     * @param queueCapacity maximum queue capacity
     * @return a fully initialised {@link EventBusException}
     */
    public static EventBusException publishOverflow(String eventType, long queueDepth, long queueCapacity) {
        return new EventBusException(KernelErrorCodes.EX_EVENT_6002, MSG_PUBLISH_OVERFLOW, null,
                eventType, queueDepth, queueCapacity);
    }
}
