/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.spi.exceptions.events;

import eu.exeris.kernel.spi.events.EventBus;
import eu.exeris.kernel.spi.exceptions.ExerisKernelException;
import eu.exeris.kernel.spi.exceptions.KernelErrorCodes;

/**
 * Thrown when an {@link EventBus} operation fails: a publish the bus did not accept, a
 * {@code publishAndAwait} whose handlers failed, or a subscription the bus rejected.
 *
 * <h2>Hierarchy &amp; java:S110</h2>
 * <p>Extends {@link ExerisKernelException} directly — one level below
 * {@code RuntimeException} in the Kernel tree — to stay within the
 * {@code java:S110} inheritance-depth limit of 5:
 * {@code Object → Throwable → Exception → RuntimeException →
 * ExerisKernelException → EventBusException}.
 *
 * <h2>rawArgs Binary Layout (Enterprise Glass-Box)</h2>
 * <p>Each static factory sets one code and fills that code's layout:
 * <ul>
 *   <li>{@link #publishOverflow(String, long, long)} → {@value KernelErrorCodes#EX_EVENT_6002}
 *       (queue overflow): {@code [String eventType, long queueDepth, long queueCapacity]}</li>
 *   <li>{@link #publishFailed(int, String, Throwable)} → {@value KernelErrorCodes#EX_EVENT_6009}
 *       (publish not accepted): {@code [int eventTypeOrdinal, String reason]}</li>
 *   <li>{@link #handlersFailed(int, int)} → {@value KernelErrorCodes#EX_EVENT_6010}
 *       (handlers failed after delivery): {@code [int eventTypeOrdinal, int failedHandlerCount]}</li>
 *   <li>{@link #subscriptionRejected(String)} → {@value KernelErrorCodes#EX_EVENT_6011}
 *       (subscription rejected): {@code [String eventType]}</li>
 * </ul>
 * <p>The two public constructors set {@value KernelErrorCodes#EX_EVENT_6001} and leave
 * {@code rawArgs} empty.
 *
 * @since 0.5
 */
public class EventBusException extends ExerisKernelException {

    private static final String MSG_PUBLISH_OVERFLOW = "Event bus queue overflow";
    private static final String MSG_PUBLISH_FAILED = "Event bus did not accept the event";
    private static final String MSG_HANDLERS_FAILED = "One or more event handlers failed";
    private static final String MSG_SUBSCRIPTION_REJECTED = "Event bus rejected the subscription";

    /**
     * Constructs a bus failure with no Glass-Box arguments — for a condition the typed factories
     * do not cover.
     *
     * @param message static message template
     * @apiNote Sets {@value KernelErrorCodes#EX_EVENT_6001} and leaves {@code rawArgs} empty, so a
     *          decoder gets the code but no structured detail. Prefer a static factory where one
     *          applies.
     */
    public EventBusException(String message) {
        super(KernelErrorCodes.EX_EVENT_6001, message, (Throwable) null);
    }

    /**
     * Constructs a bus failure that carries an upstream cause but no Glass-Box arguments.
     *
     * @param message static message template
     * @param cause   upstream throwable; may be {@code null}
     * @apiNote Sets {@value KernelErrorCodes#EX_EVENT_6001} and leaves {@code rawArgs} empty.
     *          Prefer {@link #publishFailed(int, String, Throwable)} for a publish the bus did not
     *          accept.
     */
    public EventBusException(String message, Throwable cause) {
        super(KernelErrorCodes.EX_EVENT_6001, message, cause);
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
     * @return an exception carrying {@value KernelErrorCodes#EX_EVENT_6002} and the three-element
     *         {@code rawArgs} layout above
     * @apiNote The publisher must not retry inline on this: the exception is meant to reach the
     *          caller's structured-scope boundary so the joiner policy decides whether to fail
     *          fast or shed the event.
     */
    public static EventBusException publishOverflow(String eventType, long queueDepth, long queueCapacity) {
        return new EventBusException(KernelErrorCodes.EX_EVENT_6002, MSG_PUBLISH_OVERFLOW, null,
                eventType, queueDepth, queueCapacity);
    }

    /**
     * Creates an {@code EventBusException} for a publish the bus did not accept, for a reason
     * other than a full queue.
     *
     * <p>Sets error code {@value KernelErrorCodes#EX_EVENT_6009}.
     * rawArgs layout: {@code [int eventTypeOrdinal, String reason]}.
     *
     * @param eventTypeOrdinal ordinal of the event that was not accepted
     * @param reason           static failure category — a constant, never a formatted string
     * @param cause            upstream throwable; may be {@code null}
     * @return an exception carrying {@value KernelErrorCodes#EX_EVENT_6009}, {@code cause}, and
     *         the two-element {@code rawArgs} layout above
     * @apiNote No handler was given the event by the failed call. {@code reason} reaches a
     *          decoder verbatim, so keep secrets and payload data out of it.
     * @since 0.12
     */
    public static EventBusException publishFailed(int eventTypeOrdinal, String reason, Throwable cause) {
        return new EventBusException(KernelErrorCodes.EX_EVENT_6009, MSG_PUBLISH_FAILED, cause,
                eventTypeOrdinal, reason);
    }

    /**
     * Creates an {@code EventBusException} for a {@code publishAndAwait} that delivered the event
     * and whose handlers, one or more, threw.
     *
     * <p>Sets error code {@value KernelErrorCodes#EX_EVENT_6010}.
     * rawArgs layout: {@code [int eventTypeOrdinal, int failedHandlerCount]}.
     *
     * @param eventTypeOrdinal   ordinal of the delivered event
     * @param failedHandlerCount number of handlers that threw
     * @return an exception carrying {@value KernelErrorCodes#EX_EVENT_6010} and the two-element
     *         {@code rawArgs} layout above, with no cause
     * @apiNote The caller attaches each handler's exception with {@link #addSuppressed}. The
     *          event was delivered, so a retry runs again the handlers that succeeded.
     * @since 0.12
     */
    public static EventBusException handlersFailed(int eventTypeOrdinal, int failedHandlerCount) {
        return new EventBusException(KernelErrorCodes.EX_EVENT_6010, MSG_HANDLERS_FAILED, null,
                eventTypeOrdinal, failedHandlerCount);
    }

    /**
     * Creates an {@code EventBusException} for a subscription the bus rejected.
     *
     * <p>Sets error code {@value KernelErrorCodes#EX_EVENT_6011}.
     * rawArgs layout: {@code [String eventType]}.
     *
     * @param eventType the event type name the caller subscribed to
     * @return an exception carrying {@value KernelErrorCodes#EX_EVENT_6011} and the one-element
     *         {@code rawArgs} layout above, with no cause
     * @since 0.12
     */
    public static EventBusException subscriptionRejected(String eventType) {
        return new EventBusException(KernelErrorCodes.EX_EVENT_6011, MSG_SUBSCRIPTION_REJECTED, null,
                eventType);
    }
}
