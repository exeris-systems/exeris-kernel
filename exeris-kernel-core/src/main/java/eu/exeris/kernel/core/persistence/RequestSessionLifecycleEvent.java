/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.core.persistence;

import eu.exeris.kernel.spi.persistence.TransactionIsolation;
import jdk.jfr.Category;
import jdk.jfr.Event;
import jdk.jfr.EventType;
import jdk.jfr.Label;
import jdk.jfr.Name;
import jdk.jfr.StackTrace;

/**
 * JFR event emitted by {@code PersistenceSessionBox} at every transition of a per-request
 * persistence session: acquisition, reuse, a scope bypass, or release.
 *
 * <p>{@link ConnectionHoldEvent} measures pool residency for every acquirer, including callers
 * that never bind a request session; this event is emitted from exactly one class and only when
 * a request session's box exists, making it the narrower signal for bounding hold time on
 * request-scoped work specifically.
 */
@Name("eu.exeris.kernel.persistence.RequestSessionLifecycle")
@Label("Persistence Request Session Lifecycle")
@Category({"Exeris Kernel", "Persistence"})
@StackTrace(false)
public final class RequestSessionLifecycleEvent extends Event {

    private static final EventType EVENT_TYPE =
            EventType.getEventType(RequestSessionLifecycleEvent.class);

    /**
     * Lifecycle transition reported by the caller, such as {@code ACQUIRE}, {@code REUSE},
     * {@code RELEASE}, or a {@code BYPASS_*} / {@code REJECTED_*} refusal outcome.
     */
    @Label("Operation")
    public String operation;

    /**
     * Transaction isolation the session's box was configured with, or {@code null} if none
     * was given.
     */
    @Label("Isolation")
    public String isolation;

    /** Whether the session's box was configured read-only. */
    @Label("Read Only")
    public boolean readOnly;

    /** Whether a persistence session exists on the box at the time of this event. */
    @Label("Has Session")
    public boolean hasSession;

    /**
     * Creates an unrecorded event.
     *
     * <p>{@link #emit} assigns the public fields and calls {@link Event#commit()}. An instance that is never
     * committed contributes nothing to a recording.
     */
    public RequestSessionLifecycleEvent() {
        // Declared, not added: the implicit no-arg constructor, written out so it can carry a comment.
        super();
    }

    /**
     * Commits a request-session-lifecycle event, or does nothing if the event type is disabled.
     *
     * @param operation  lifecycle transition being reported; see {@link #operation}
     * @param isolation  transaction isolation the session's box was configured with, or
     *                   {@code null} if none was given
     * @param readOnly   whether the session's box was configured read-only
     * @param hasSession whether a persistence session exists on the box at the time of this event
     */
    public static void emit(String operation,
                            TransactionIsolation isolation,
                            boolean readOnly,
                            boolean hasSession) {
        if (!EVENT_TYPE.isEnabled()) {
            return;
        }
        RequestSessionLifecycleEvent event = new RequestSessionLifecycleEvent();
        event.operation = operation;
        if (isolation != null) {
            event.isolation = isolation.name();
        }
        event.readOnly = readOnly;
        event.hasSession = hasSession;
        event.commit();
    }
}