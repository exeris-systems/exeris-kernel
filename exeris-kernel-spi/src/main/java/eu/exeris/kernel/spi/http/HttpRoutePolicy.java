/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.spi.http;

import java.util.Set;

/**
 * SPI: the application's statement of what each of its HTTP routes requires (ADR-061).
 *
 * <h2>Why this exists</h2>
 * <p>Before 0.11 the kernel's admission gate was steered by constants compiled into the Community
 * driver — a {@code /secure} path prefix and two literal scope names. The gate worked, but no
 * application could address it: a route policy is a property of the application's own URL space, and
 * there was no way to state one. Everything outside the prefix reached its handler with no identity
 * bound at all.
 *
 * <h2>Contract</h2>
 * <p>{@link #requirementFor(HttpMethod, String)} is called once per request, before the handler runs.
 * It must be:
 * <ul>
 *   <li><b>Total.</b> Every {@code (method, path)} pair gets an answer, and never {@code null} —
 *       see {@link #unmatched()} for how to say "nothing declared for this route", which is a
 *       decision, not an absence. A {@code null} answer is treated as a policy defect and denies
 *       the request outright; it is <em>not</em> read as {@link #unmatched()}, because that would
 *       let a broken policy admit any authenticated caller to a route it never described.</li>
 *   <li><b>Pure and thread-safe.</b> It runs on the admission path of every request, concurrently.</li>
 *   <li><b>Allocation-free.</b> Return pre-built {@link RouteRequirement} instances. Building one per
 *       request puts allocation on the admission path, which the performance contract does not allow
 *       for the neighbouring RBAC decision either (ADR-014 §5).</li>
 * </ul>
 *
 * <h2>The unmatched route is the dangerous case</h2>
 * <p>A policy that declares rules for {@code /api/orders/**} says nothing about {@code /api/internal}
 * — and what happens to that request is the whole security posture. This contract does not choose for
 * you, but it does force you to choose: {@link #unmatched()} names the two options, and a policy must
 * return one of them rather than leaving the question implicit. Returning
 * {@link RouteRequirement#permitAll()} for unmatched routes is fail-open and must be a decision the
 * application makes knowingly.
 *
 * <h2>Binding</h2>
 * <p>Supplied by the application and bound into {@link HttpKernelProviders#HTTP_ROUTE_POLICY}. When
 * the slot is unbound, the kernel applies no per-route requirement — behaviour matches a kernel with
 * no policy at all. Read defensively via {@link HttpKernelProviders#httpRoutePolicy()}.
 *
 * @since 0.11.0
 */
@FunctionalInterface
public interface HttpRoutePolicy {

    /**
     * Returns what the given route requires of the caller.
     *
     * @param method the request method; never {@code null}
     * @param path   the request path, without query string; never {@code null}
     * @return the requirement; never {@code null}
     */
    RouteRequirement requirementFor(HttpMethod method, String path);

    /**
     * Documents the two answers available for a route the policy does not describe.
     *
     * <p>This is not a factory to call at request time — it is here so the choice has a name and a
     * place in the contract. {@link RouteRequirement#authenticated()} is fail-closed: an undeclared
     * route still demands identity. {@link RouteRequirement#permitAll()} is fail-open: an undeclared
     * route is public, which is how a forgotten endpoint becomes an unauthenticated one.
     *
     * @return the fail-closed answer, which is the one to prefer when unsure
     */
    static RouteRequirement unmatched() {
        return RouteRequirement.authenticated();
    }

    /**
     * A policy that requires nothing of any route.
     *
     * <p>Equivalent to leaving the slot unbound, and useful as an explicit "this deployment declares
     * no route policy" rather than an omission a reader has to infer.
     *
     * @return a policy answering {@link RouteRequirement#permitAll()} everywhere
     */
    static HttpRoutePolicy permitAll() {
        return (method, path) -> RouteRequirement.permitAll();
    }

    /**
     * A policy requiring a verified identity on every route.
     *
     * @return a policy answering {@link RouteRequirement#authenticated()} everywhere
     */
    static HttpRoutePolicy authenticated() {
        return (method, path) -> RouteRequirement.authenticated();
    }

    /**
     * A policy requiring a verified identity holding at least one of the given scopes on every route.
     *
     * <p>The blunt instrument: useful for a service whose entire surface is one permission, and as a
     * starting point to narrow from.
     *
     * @param scopes the accepted scopes; must be non-null and non-empty
     * @return a policy answering the same scope requirement everywhere
     */
    static HttpRoutePolicy requiringAnyScope(Set<String> scopes) {
        RouteRequirement requirement = RouteRequirement.requiringAnyScope(scopes);
        return (method, path) -> requirement;
    }
}
