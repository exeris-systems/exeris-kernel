/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.http;

import eu.exeris.kernel.core.http.routing.HttpRouter;
import eu.exeris.kernel.spi.http.HttpHandler;
import eu.exeris.kernel.spi.http.HttpMethod;
import eu.exeris.kernel.spi.http.HttpRequest;
import eu.exeris.kernel.spi.http.HttpRoutePolicy;
import eu.exeris.kernel.spi.http.RouteRequirement;

/**
 * Asks an {@link HttpRoutePolicy} about the route a request is dispatched to, rather than about the
 * request-target as sent.
 *
 * <p>The two differ where the router normalises a request before matching it. The query component
 * never takes part in matching, so it is removed. A respond-once {@code HEAD} request that no
 * {@code HEAD} route matches is served by the {@code GET} route (RFC 9110 §9.3.2), so it is asked as
 * {@code GET}; one the router dispatches to a registered {@code HEAD} route is asked as {@code HEAD}.
 * A stream route has no such fallback, so a stream open keeps its method. Asking anything else lets
 * the policy and the router disagree about which route a request is — and a request the policy does
 * not recognise, but the router does, reaches the handler under the policy's answer for an undeclared
 * route instead of the route's own requirement.
 *
 * <p><b>Allocation:</b> none for the path, except one substring when the request-target carries a
 * query; a respond-once {@code HEAD} request also allocates what the router's resolution allocates.
 *
 * <p><b>Thread confinement:</b> any thread — stateless.
 *
 * <p><b>Ownership:</b> owns nothing; the policy, the request and the handler are borrowed for the
 * call.
 */
final class RoutePolicyQuery {

    private RoutePolicyQuery() {
    }

    /**
     * Returns what {@code policy} requires of the route {@code request} is dispatched to.
     *
     * <p>Only an {@link HttpRouter} is known to serve {@code HEAD} from a {@code GET} route; behind any
     * other root handler, and for a stream open, the request's own method is the route's.
     *
     * @param policy  the bound route policy; must not be {@code null}
     * @param request the parsed request; must not be {@code null}
     * @param handler the root handler a respond-once request is dispatched to, or {@code null} for a
     *                stream open, whose route is resolved without a {@code HEAD} fallback
     * @return the policy's answer, passed through unchanged — including a {@code null}, which the
     *         caller treats as a policy defect
     */
    /* default */ static RouteRequirement requirementFor(HttpRoutePolicy policy, HttpRequest request,
                                                         HttpHandler handler) {
        HttpMethod method = handler instanceof HttpRouter router
                ? router.routeMethod(request.method(), request.path())
                : request.method();
        return policy.requirementFor(method, routePath(request.path()));
    }

    /**
     * The request-target with its query component removed.
     *
     * @param requestTarget the request-target as received
     * @return the path the router matches on
     */
    private static String routePath(String requestTarget) {
        int query = requestTarget.indexOf('?');
        return query < 0 ? requestTarget : requestTarget.substring(0, query);
    }
}
