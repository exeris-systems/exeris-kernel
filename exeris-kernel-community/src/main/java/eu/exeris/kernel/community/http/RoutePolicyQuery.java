/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.http;

import eu.exeris.kernel.spi.http.HttpMethod;
import eu.exeris.kernel.spi.http.HttpRequest;
import eu.exeris.kernel.spi.http.HttpRoutePolicy;
import eu.exeris.kernel.spi.http.RouteRequirement;

/**
 * Asks an {@link HttpRoutePolicy} about the route a request is dispatched to, rather than about the
 * request-target as sent.
 *
 * <p>The two differ in exactly the ways the router normalises a request before matching it: the query
 * component is removed, and {@code HEAD} is served by the {@code GET} route (RFC 9110 §9.3.2). The
 * policy must be asked the same question, or the policy and the router disagree about which route a
 * request is — and a request the policy does not recognise, but the router does, reaches the handler
 * under the policy's answer for an undeclared route instead of the route's own requirement.
 *
 * <p><b>Allocation:</b> none, except one substring when the request-target carries a query
 * <p><b>Thread confinement:</b> any thread — stateless
 */
final class RoutePolicyQuery {

    private RoutePolicyQuery() {
    }

    /**
     * Returns what {@code policy} requires of the route {@code request} is dispatched to.
     *
     * @param policy  the bound route policy; must not be {@code null}
     * @param request the parsed request; must not be {@code null}
     * @return the policy's answer, passed through unchanged — including a {@code null}, which the
     *         caller treats as a policy defect
     */
    /* default */ static RouteRequirement requirementFor(HttpRoutePolicy policy, HttpRequest request) {
        HttpMethod method = request.method() == HttpMethod.HEAD ? HttpMethod.GET : request.method();
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
