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
 * JFR event emitted when a {@code permitAll()} route's request session was opened for a storage
 * context that declares no tenant.
 *
 * <h2>What it is for</h2>
 * <p>A {@code permitAll()} route runs no security interceptor, so nothing binds
 * {@code KernelProviders.STORAGE_CONTEXT} for it. A handler on such a route that reaches persistence
 * through the ambient-context path — {@code PersistenceEngine.openConnection()}, and everything
 * built on it — receives a connection scoped to the system context, whose tenant key is empty. No
 * exception is raised on that path, by contract. This event is what makes it visible: it names the
 * public route that took a request session without a tenant scope, so an operator can tell a
 * deliberate system read from a route that expected a tenant and did not get one.
 *
 * <p>What is recorded is the context the session's connection was actually opened for, not the
 * state of the {@code STORAGE_CONTEXT} slot. A context declares no tenant when its isolation key
 * is absent or blank — the case in which the tenant session key is published as {@code ''}. So a
 * handler that passes a tenant context to {@code openConnection(StorageContext)} is not reported,
 * bound slot or not; and a handler that chooses the system context on a public route, by passing
 * it or by binding it, is reported, because a deliberate system scope on a public route is exactly
 * what an operator should be able to see.
 *
 * <p>Only a route whose resolved requirement is {@code PERMIT_ALL} is reported. An authenticated
 * route whose security provider resolved a context without a tenant is a tenant-less deployment's
 * legitimate answer, and reporting it would put one event on every request that reaches
 * persistence.
 *
 * <p>The event fires on every matching request, not once per route: a health check on a
 * {@code permitAll()} route that reads the database emits it on every probe. That is the case an
 * operator is meant to see; a deployment that has accepted it disables this event in its JFR
 * settings.
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
@Description("A permitAll route's persistence session was opened for a storage context that declares "
        + "no tenant; its connection is scoped to the system context")
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
     * @param readOnly  whether the session was requested read-only
     */
    public static void emit(String method, String path, boolean readOnly) {
        if (!EVENT_TYPE.isEnabled()) {
            return;
        }
        CommunityUnscopedRequestSessionEvent event = new CommunityUnscopedRequestSessionEvent();
        event.method = method;
        event.path = path;
        event.readOnly = readOnly;
        event.commit();
    }
}
