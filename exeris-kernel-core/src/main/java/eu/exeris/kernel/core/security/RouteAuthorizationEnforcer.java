/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.core.security;

import eu.exeris.kernel.spi.http.HttpMethod;
import eu.exeris.kernel.spi.http.HttpRoutePolicy;
import eu.exeris.kernel.spi.http.RouteRequirement;
import eu.exeris.kernel.spi.security.PrincipalContext;

/**
 * Core: the per-request route-authorization decision (ADR-061).
 *
 * <h2>Why Core and not the driver</h2>
 * <p>This is the sibling of {@link RoleCheckEnforcer}, and it sits here for the same reason: ADR-014 §4
 * places runtime admission integration in Core, so every transport inherits one decision layer rather
 * than each driver growing its own. It is also what lets a host-runtime binding translate its own rule
 * DSL onto this decision instead of building a second authorization mechanism beside it.
 *
 * <h2>Three outcomes, not two</h2>
 * <p>{@link RouteDecision} separates "no identity" from "identity without the required scope", because
 * the caller must map them to different HTTP statuses — {@code 401} and {@code 403} respectively, as
 * fixed by ADR-012 and documented in {@code security.md}. Collapsing them would tell an authenticated
 * caller to authenticate again.
 *
 * <h2>Allocation</h2>
 * <p>No allocation on any path: the scope walk uses {@link RouteRequirement#scopeCount()} /
 * {@link RouteRequirement#scopeAt(int)} rather than an iterator, and the outcome is an enum constant.
 *
 * @since 0.11.0
 */
public final class RouteAuthorizationEnforcer {

    private RouteAuthorizationEnforcer() {
        // Static decision helper — never instantiated, mirroring RoleCheckEnforcer.
    }

    /** Outcome of a route-authorization decision. */
    public enum RouteDecision {
        /** The request may proceed to its handler. */
        ADMIT,
        /** No verified identity is present; the caller must authenticate ({@code 401}). */
        UNAUTHENTICATED,
        /** A verified identity is present but lacks the declared scopes ({@code 403}). */
        FORBIDDEN
    }

    /**
     * Decides whether a request satisfies the requirement its route declares.
     *
     * <p>A {@code null} requirement means the policy broke its own contract, and a broken policy must
     * not grant. Reading it as {@link HttpRoutePolicy#unmatched()} would admit any authenticated
     * caller — so a policy bug would hand an ordinary user a route that may have demanded an admin
     * scope. It denies outright instead, in the shape the caller can act on: no identity is still a
     * {@code 401}, an identity we cannot authorize is a {@code 403}. This method owns that rule, so
     * callers that resolve a requirement themselves need not re-derive it.
     *
     * @param requirement what the route demands, or {@code null} if the policy returned none
     * @param principal   the verified identity, or {@code null} when none was established
     * @return the decision
     */
    public static RouteDecision decide(RouteRequirement requirement, PrincipalContext principal) {
        if (requirement == null) {
            return deny(principal);
        }
        if (requirement.kind() == RouteRequirement.Kind.PERMIT_ALL) {
            return RouteDecision.ADMIT;
        }
        if (principal == null) {
            return RouteDecision.UNAUTHENTICATED;
        }
        return satisfiesScopes(requirement, principal) ? RouteDecision.ADMIT : RouteDecision.FORBIDDEN;
    }

    /** Denies in the shape the caller can act on: {@code 401} with no identity, {@code 403} with one. */
    private static RouteDecision deny(PrincipalContext principal) {
        return principal == null ? RouteDecision.UNAUTHENTICATED : RouteDecision.FORBIDDEN;
    }

    private static boolean satisfiesScopes(RouteRequirement requirement, PrincipalContext principal) {
        // Exhaustive over Kind on purpose: a new variant fails the compile here rather than falling
        // through to true, which would open every route carrying it.
        return switch (requirement.kind()) {
            case PERMIT_ALL, AUTHENTICATED -> true;
            case ANY_SCOPE -> hasAny(requirement, principal);
            case ALL_SCOPES -> hasAll(requirement, principal);
        };
    }

    /**
     * Resolves the route's requirement through the policy and decides in one step.
     *
     * @param policy    the application's route policy; must not be {@code null}
     * @param method    the request method
     * @param path      the request path, without query string
     * @param principal the verified identity, or {@code null} when none was established
     * @return the decision
     */
    public static RouteDecision decide(HttpRoutePolicy policy,
                                       HttpMethod method,
                                       String path,
                                       PrincipalContext principal) {
        return decide(policy.requirementFor(method, path), principal);
    }

    private static boolean hasAny(RouteRequirement requirement, PrincipalContext principal) {
        for (int i = 0; i < requirement.scopeCount(); i++) {
            if (principal.hasScope(requirement.scopeAt(i))) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasAll(RouteRequirement requirement, PrincipalContext principal) {
        for (int i = 0; i < requirement.scopeCount(); i++) {
            if (!principal.hasScope(requirement.scopeAt(i))) {
                return false;
            }
        }
        return true;
    }
}
