/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
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
 * @since 0.5
 */
public class EventRegistryException extends ExerisKernelException {

    private static final String MSG_DUPLICATE  = "Event type already registered with different settings";
    private static final String MSG_POST_START = "Event type registration rejected after engine start";

    /**
     * Constructs a registry failure with no Glass-Box arguments.
     *
     * @param message static message template
     * @apiNote Sets {@value KernelErrorCodes#EX_EVENT_6003} and leaves {@code rawArgs} empty.
     *          Prefer {@link #duplicateConflict(String, int)} or
     *          {@link #postStartRejected(String)} where they apply.
     */
    public EventRegistryException(String message) {
        super(KernelErrorCodes.EX_EVENT_6003, message, (Throwable) null);
    }

    /**
     * Constructs a registry failure that carries an upstream cause but no Glass-Box arguments.
     *
     * @param message static message template
     * @param cause   upstream throwable; may be {@code null}
     * @apiNote Sets {@value KernelErrorCodes#EX_EVENT_6003} and leaves {@code rawArgs} empty.
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
     * @return an exception carrying {@value KernelErrorCodes#EX_EVENT_6003} and the two-element
     *         {@code rawArgs} layout above
     */
    public static EventRegistryException duplicateConflict(String eventType, int ordinal) {
        return new EventRegistryException(KernelErrorCodes.EX_EVENT_6003, MSG_DUPLICATE, null,
                eventType, ordinal);
    }

    /**
     * Creates an {@code EventRegistryException} for a registration attempted after the engine
     * fixed its routing table at start.
     *
     * <p>Sets error code {@value KernelErrorCodes#EX_EVENT_6003}.
     * rawArgs layout: {@code [String eventType, int ordinal]}, with the ordinal reported as
     * {@code -1} — no ordinal was ever claimed, because the registration was refused outright.
     *
     * @param eventType the event type name that was rejected
     * @return an exception carrying {@value KernelErrorCodes#EX_EVENT_6003}
     */
    public static EventRegistryException postStartRejected(String eventType) {
        return new EventRegistryException(KernelErrorCodes.EX_EVENT_6003, MSG_POST_START, null,
                eventType, -1);
    }
}
