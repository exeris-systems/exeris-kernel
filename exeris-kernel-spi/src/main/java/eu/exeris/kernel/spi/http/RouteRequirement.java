/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.spi.http;

import java.util.Objects;
import java.util.Set;

/**
 * SPI: what a route demands of the caller before a handler may run (ADR-061).
 *
 * <h2>Three shapes, deliberately</h2>
 * <ul>
 *   <li>{@link #permitAll()} — no identity required. The handler runs without a
 *       {@code PrincipalContext} bound.</li>
 *   <li>{@link #authenticated()} — a verified identity is required; nothing more.</li>
 *   <li>{@link #requiringAnyScope(Set)} / {@link #requiringAllScopes(Set)} — a verified identity carrying the
 *       named OAuth2 scopes.</li>
 * </ul>
 *
 * <p>Roles are deliberately absent. Scopes are what the transport edge checks — {@code security.md}
 * states it plainly: "scopes are used for HTTP admission control and Bearer token permission checks",
 * while roles resolve through {@code @RequiresRole} at the method level, against a build-time
 * {@code methodId} the kernel cannot derive from a URL. Offering a role predicate here would invite
 * callers to express at the edge something the edge cannot answer.
 *
 * <h2>Allocation</h2>
 * <p>Instances are immutable and meant to be built once, at policy-declaration time, and returned
 * repeatedly. A {@link HttpRoutePolicy} that constructs a requirement per request moves allocation
 * onto the admission path and breaks the performance contract ADR-014 §5 fixes for the neighbouring
 * RBAC decision. {@link #permitAll()} and {@link #authenticated()} return shared constants.
 *
 * @since 0.11.0
 */
// TooManyMethods: ten methods on an immutable value carrier — four factories (one per shape the
// contract offers), two accessors that exist specifically to keep the decision path allocation-free,
// kind(), and the three Object overrides a Valhalla-ready carrier is obliged to define. Splitting the
// type to satisfy the count would put the shapes and the reads in different places for no gain.
@SuppressWarnings("PMD.TooManyMethods")
public value class RouteRequirement {

    private static final RouteRequirement PERMIT_ALL = new RouteRequirement(Kind.PERMIT_ALL, Set.of());
    private static final RouteRequirement AUTHENTICATED =
            new RouteRequirement(Kind.AUTHENTICATED, Set.of());

    /** How the declared scopes are matched. */
    public enum Kind {
        /** No identity required. */
        PERMIT_ALL,
        /** Verified identity required; no scope check. */
        AUTHENTICATED,
        /** Verified identity holding at least one of the declared scopes. */
        ANY_SCOPE,
        /** Verified identity holding every declared scope. */
        ALL_SCOPES
    }

    private final Kind kind;
    private final Set<String> scopes;
    private final String[] scopeArray;

    private RouteRequirement(Kind kind, Set<String> scopes) {
        this.kind = kind;
        this.scopes = scopes;
        // Pre-flattened so the decision path iterates an array instead of an iterator over a Set.
        this.scopeArray = scopes.toArray(new String[0]);
    }

    /**
     * A route open to unauthenticated callers.
     *
     * @return the shared permit-all requirement
     */
    public static RouteRequirement permitAll() {
        return PERMIT_ALL;
    }

    /**
     * A route requiring a verified identity and nothing further.
     *
     * @return the shared authenticated-only requirement
     */
    public static RouteRequirement authenticated() {
        return AUTHENTICATED;
    }

    /**
     * A route requiring a verified identity holding at least one of {@code requiredScopes}.
     *
     * @param requiredScopes the accepted scopes; must be non-null and non-empty
     * @return the requirement
     * @throws IllegalArgumentException if {@code requiredScopes} is empty — an empty any-of set can
     *                                  never be satisfied, so accepting it would silently create a
     *                                  route nobody can call
     */
    public static RouteRequirement requiringAnyScope(Set<String> requiredScopes) {
        return new RouteRequirement(Kind.ANY_SCOPE, validated(requiredScopes));
    }

    /**
     * A route requiring a verified identity holding every one of {@code requiredScopes}.
     *
     * @param requiredScopes the required scopes; must be non-null and non-empty
     * @return the requirement
     * @throws IllegalArgumentException if {@code requiredScopes} is empty — an empty all-of set is
     *                                  vacuously true, which would silently downgrade the route to
     *                                  {@link #authenticated()}
     */
    public static RouteRequirement requiringAllScopes(Set<String> requiredScopes) {
        return new RouteRequirement(Kind.ALL_SCOPES, validated(requiredScopes));
    }

    /**
     * Returns how this requirement is evaluated.
     *
     * @return the requirement kind; never {@code null}
     */
    public Kind kind() {
        return kind;
    }

    /**
     * Returns how many scopes this requirement declares.
     *
     * <p>Paired with {@link #scopeAt(int)} so the decision path can walk the scopes without
     * allocating an iterator, and without the carrier handing out its mutable backing array. This
     * pair is the only read: a {@code Set}-returning accessor beside it would be a second way to the
     * same field, and the convenient one is the one that allocates on the admission path.
     *
     * @return the scope count; zero for {@link Kind#PERMIT_ALL} and {@link Kind#AUTHENTICATED}
     */
    public int scopeCount() {
        return scopeArray.length;
    }

    /**
     * Returns the scope at {@code index}, in no guaranteed order.
     *
     * @param index position in {@code [0, scopeCount())}
     * @return the scope name
     * @throws ArrayIndexOutOfBoundsException if {@code index} is out of range
     */
    public String scopeAt(int index) {
        return scopeArray[index];
    }

    private static Set<String> validated(Set<String> requiredScopes) {
        Objects.requireNonNull(requiredScopes, "requiredScopes must not be null");
        if (requiredScopes.isEmpty()) {
            throw new IllegalArgumentException("requiredScopes must not be empty");
        }
        return Set.copyOf(requiredScopes);
    }

    @Override
    public boolean equals(Object other) {
        // Value equality, not identity: permitAll() and authenticated() hand out shared constants, so
        // a caller comparing requirements with == would be right by accident until someone built an
        // equivalent one by hand. Valhalla-ready carriers must not be identity-sensitive.
        return other instanceof RouteRequirement that
                && kind == that.kind
                && scopes.equals(that.scopes);
    }

    @Override
    public int hashCode() {
        return Objects.hash(kind, scopes);
    }

    @Override
    public String toString() {
        return "RouteRequirement[" + kind + (scopes.isEmpty() ? "" : ", scopes=" + scopes) + ']';
    }
}
