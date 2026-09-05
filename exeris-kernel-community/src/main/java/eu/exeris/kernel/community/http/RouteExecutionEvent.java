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
import jdk.jfr.Timespan;

/**
 * JFR event emitted when a route declared {@code LONG_RUNNING} (ADR-077) finishes.
 *
 * <h2>What it is for</h2>
 * <p>ADR-077 accepts one cost by name: a declaration can go stale. A route marked
 * {@code LONG_RUNNING} that stops blocking keeps paying extra connection acquires — and therefore
 * extra {@code RlsConnectionInterceptor} round-trips — forever, silently. This event is what makes
 * the mismatch detectable rather than theoretical.
 *
 * <p>It carries the duration and lets the consumer decide what counts as stale, deliberately: a
 * threshold baked in here would be a magic number the kernel cannot justify for every deployment,
 * and the Glass-Box contract puts that judgement in the recording, not in the runtime.
 *
 * <h2>The path field, named rather than slipped in</h2>
 * <p>This is the <b>first kernel JFR event to carry a request path</b>. Nothing identifies a stale
 * declaration without it — an operator needs to know <em>which</em> route is paying for nothing —
 * and the exposure is bounded: the event fires only on routes the application itself declared
 * {@code LONG_RUNNING}, and the value is the same data any access log already records. It is still
 * caller-supplied and may embed identifiers, so a deployment recording this event is recording
 * request paths. That is the trade; it is not hidden in a field list.
 *
 * <p><b>Single-phase commit.</b> The duration is measured by the dispatcher and passed in, rather
 * than taken from {@code begin()}/{@code commit()} around the handler. A JFR event straddling a
 * blocking operation on a virtual thread is a known crash shape in this repository, and a handler
 * declared {@code LONG_RUNNING} is by definition one that blocks.
 *
 * @since 0.12
 */
@Name("eu.exeris.kernel.http.RouteExecution")
@Label("HTTP Route Execution")
@Description("A route declared LONG_RUNNING (ADR-077) completed; duration reveals a stale declaration")
@Category({"Exeris Kernel", "HTTP"})
@StackTrace(false)
public final class RouteExecutionEvent extends Event {

    private static final EventType EVENT_TYPE = EventType.getEventType(RouteExecutionEvent.class);

    @Label("Method")
    public String method;

    @Label("Path")
    public String path;

    @Label("Declared Execution")
    public String declaredExecution;

    @Label("Handler Duration")
    @Timespan(Timespan.NANOSECONDS)
    public long handlerDurationNs;

    /**
     * Emits the completion of a {@code LONG_RUNNING} route.
     *
     * @param method            the request method name
     * @param path              the request path
     * @param handlerDurationNs how long the handler ran, measured by the caller
     */
    public static void emitLongRunning(String method, String path, long handlerDurationNs) {
        if (!EVENT_TYPE.isEnabled()) {
            return;
        }
        RouteExecutionEvent event = new RouteExecutionEvent();
        event.method = method;
        event.path = path;
        event.declaredExecution = "LONG_RUNNING";
        event.handlerDurationNs = handlerDurationNs;
        event.commit();
    }
}
