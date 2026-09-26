/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.http;

import jdk.jfr.Category;
import jdk.jfr.Description;
import jdk.jfr.Event;
import jdk.jfr.EventType;
import jdk.jfr.Label;
import jdk.jfr.Name;
import jdk.jfr.StackTrace;

/**
 * JFR event emitted when a request's persistence session was acquired with no
 * {@code StorageContext} bound.
 *
 * <h2>What it is for</h2>
 * <p>A {@code permitAll()} route runs no security interceptor, so nothing binds
 * {@code KernelProviders.STORAGE_CONTEXT} for it. A handler on such a route that reaches persistence
 * through the ambient-context path — {@code PersistenceEngine.openConnection()}, and everything
 * built on it — receives a connection scoped to the system context, whose tenant key is empty. No
 * exception is raised on that path, by contract. This event is what makes it visible: it names the
 * route that took a request session without a tenant scope, so an operator can tell a deliberate
 * system read on a public route from a route that expected a tenant and did not get one.
 *
 * <p>{@code routeKind} carries the resolved {@code RouteRequirement.Kind}. On a route that ran the
 * security interceptor the context is bound before the handler runs, so any kind other than
 * {@code PERMIT_ALL} in this field signals a defect rather than a public route.
 *
 * <p>The session box records whether the slot was bound at the moment it acquired, not which
 * context the opener used: a handler that passes an explicit context to
 * {@code openConnection(StorageContext)} without binding the slot is recorded as well. Binding
 * {@code STORAGE_CONTEXT} around the persistence call is what removes a route from this event.
 *
 * <p>A {@code LONG_RUNNING} route has no request session, so this event cannot observe it.
 *
 * <h2>The path field</h2>
 * <p>This event carries the request path, with the same trade {@link RouteExecutionEvent} states:
 * nothing identifies the route without it, and the value is the data an access log already
 * records, but it is caller-supplied and may embed identifiers, so a deployment recording this
 * event is recording request paths.
 *
 * <p><b>Single-phase commit.</b> The dispatcher emits it once the handler has returned and before
 * the session is released, so a failure during release cannot suppress it; it never brackets the
 * handler with {@code begin()}/{@code commit()}, which on a virtual thread that blocks is a known
 * crash shape.
 *
 * @since 0.12
 */
@Name("eu.exeris.kernel.security.UnscopedRequestSession")
@Label("Unscoped Request Session")
@Description("A request's persistence session was acquired with no StorageContext bound; "
        + "its connection is scoped to the system context")
@Category({"Exeris Kernel", "Security"})
@StackTrace(false)
public final class CommunityUnscopedRequestSessionEvent extends Event {

    private static final EventType EVENT_TYPE =
            EventType.getEventType(CommunityUnscopedRequestSessionEvent.class);

    /** The request's method, as its wire token (e.g. {@code "GET"}). */
    @Label("Method")
    public String method;

    /** The request's path. */
    @Label("Path")
    public String path;

    /**
     * The resolved route requirement's kind, as its constant name; {@code "PERMIT_ALL"} for a
     * public route, and anything else a defect.
     */
    @Label("Route Kind")
    public String routeKind;

    /** Whether the session was requested read-only, which the dispatcher derives from the method. */
    @Label("Read Only")
    public boolean readOnly;

    /**
     * Constructed by {@link #emit} once {@link #EVENT_TYPE} is confirmed enabled — and,
     * reflectively, by the JFR runtime when this event type is registered — with every field left
     * unset until {@code emit} assigns them and commits.
     */
    public CommunityUnscopedRequestSessionEvent() {
        // Declared, not added: the implicit no-arg constructor, written out so it can carry a comment.
        super();
    }

    /**
     * Emits one unscoped request session; allocates nothing when the event is disabled.
     *
     * @param method    the request method name
     * @param path      the request path
     * @param routeKind the resolved route requirement's kind name
     * @param readOnly  whether the session was requested read-only
     */
    public static void emit(String method, String path, String routeKind, boolean readOnly) {
        if (!EVENT_TYPE.isEnabled()) {
            return;
        }
        CommunityUnscopedRequestSessionEvent event = new CommunityUnscopedRequestSessionEvent();
        event.method = method;
        event.path = path;
        event.routeKind = routeKind;
        event.readOnly = readOnly;
        event.commit();
    }
}
