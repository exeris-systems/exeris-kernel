/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.spi.exceptions.events;

import eu.exeris.kernel.spi.events.EventRegistry;
import eu.exeris.kernel.spi.exceptions.ExerisKernelException;
import eu.exeris.kernel.spi.exceptions.KernelErrorCodes;

/**
 * Thrown when an {@link EventRegistry} operation fails (duplicate registration with conflict,
 * registration after engine start in Enterprise mode).
 *
 * <h2>Hierarchy &amp; java:S110</h2>
 * <p>Extends {@link ExerisKernelException} directly — one level below
 * {@code RuntimeException} in the Kernel tree — to stay within the
 * {@code java:S110} inheritance-depth limit of 5:
 * {@code Object → Throwable → Exception → RuntimeException →
 * ExerisKernelException → EventRegistryException}.
 *
 * <h2>rawArgs Binary Layout (Enterprise Glass-Box)</h2>
 * <p>For {@value KernelErrorCodes#EX_EVENT_6003} (registry conflict):
 * <ul>
 *   <li>index 0 – {@code String} eventType  (conflicting type name)</li>
 *   <li>index 1 – {@code int}    ordinal    (ordinal already in use)</li>
 * </ul>
 *
 * @since 0.5.0
 */
public class EventRegistryException extends ExerisKernelException {

    private static final String MSG_DUPLICATE  = "Event type already registered with different settings";
    private static final String MSG_POST_START = "Event type registration rejected after engine start";

    /**
     * General-purpose constructor.
     *
     * @param message static message template
     */
    public EventRegistryException(String message) {
        super(KernelErrorCodes.EX_EVENT_6003, message, (Throwable) null);
    }

    /**
     * General-purpose constructor with cause.
     *
     * @param message static message template
     * @param cause   upstream throwable; may be {@code null}
     */
    public EventRegistryException(String message, Throwable cause) {
        super(KernelErrorCodes.EX_EVENT_6003, message, cause);
    }

    // Full-args constructor for factory methods — must precede static factory methods per DeclarationOrder
    private EventRegistryException(String errorCode, String message, Throwable cause, Object... rawArgs) {
        super(errorCode, message, cause, rawArgs);
    }

    /**
     * Creates an {@code EventRegistryException} for a duplicate registration conflict.
     *
     * <p>Sets error code {@value KernelErrorCodes#EX_EVENT_6003}.
     * rawArgs layout: {@code [String eventType, int ordinal]}.
     *
     * @param eventType the conflicting event type name
     * @param ordinal   the ordinal that is already in use
     * @return a fully initialised {@link EventRegistryException}
     */
    public static EventRegistryException duplicateConflict(String eventType, int ordinal) {
        return new EventRegistryException(KernelErrorCodes.EX_EVENT_6003, MSG_DUPLICATE, null,
                eventType, ordinal);
    }

    /**
     * Creates an {@code EventRegistryException} for a post-start registration attempt.
     *
     * @param eventType the event type name that was rejected
     * @return a fully initialised {@link EventRegistryException}
     */
    public static EventRegistryException postStartRejected(String eventType) {
        return new EventRegistryException(KernelErrorCodes.EX_EVENT_6003, MSG_POST_START, null,
                eventType, -1);
    }
}
