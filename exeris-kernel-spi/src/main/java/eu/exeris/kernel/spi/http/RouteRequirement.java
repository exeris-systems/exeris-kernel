/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
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
 * <p>{@link #abstain()} is a fourth answer but not a fourth shape: it declares that this policy does
 * not describe the route at all, which is what lets two policies share one URL space.
 *
 * <p>Roles are deliberately absent. Scopes are what the transport edge checks — {@code security.md}
 * states it plainly: "scopes are used for HTTP admission control and Bearer token permission checks",
 * while roles resolve through {@code @RequiresRole} at the method level, against a build-time
 * {@code methodId} the kernel cannot derive from a URL. Offering a role predicate here would invite
 * callers to express at the edge something the edge cannot answer.
 *
 * <h2>How the route executes (ADR-077)</h2>
 * <p>A requirement also declares whether the handler returns promptly or blocks —
 * {@link Execution#PROMPT} by default, {@link Execution#LONG_RUNNING} via {@link #longRunning()}.
 * The facet describes the <em>route</em>, deliberately not a connection: this package must stay
 * blind to what a driver holds, so the Community dispatcher is what draws the consequence (it binds
 * no request-scoped persistence session across a {@code LONG_RUNNING} route). Another driver may
 * draw a different one, or none.
 *
 * <p>The facet is opt-in: a policy that names no execution declares {@link Execution#PROMPT}.
 *
 * <p><b>Allocation:</b> zero-alloc on hot path — {@link #kind()}, {@link #execution()},
 * {@link #scopeCount()} and {@link #scopeAt(int)} allocate nothing, while {@link #permitAll()},
 * {@link #authenticated()} and {@link #abstain()} hand back shared constants, as does
 * {@link #longRunning()} on the first two; the scope-carrying factories allocate, once, at
 * policy-declaration time.
 * <p><b>Thread confinement:</b> any thread — instances are immutable and expose no mutable state,
 * so one built at policy-declaration time is read safely from every thread that later decides a
 * request.
 * <p><b>Ownership:</b> nothing to release — a requirement is a plain value the policy that built it
 * may hold and hand out for the life of the process.
 *
 * @apiNote Build a requirement once, at policy-declaration time, and return it repeatedly. An
 *          {@link HttpRoutePolicy} that constructs one per request moves allocation onto the
 *          admission path and breaks the performance contract ADR-014 §5 fixes for the neighbouring
 *          RBAC decision.
 * @since 0.11
 */
// TooManyMethods: twelve methods on an immutable value carrier — four factories (one per shape the
// contract offers), two accessors that exist specifically to keep the decision path allocation-free,
// kind(), execution() and longRunning() (ADR-077's facet, a reader and a wither rather than four more
// factories), and the three Object overrides a Valhalla-ready carrier is obliged to define. Splitting
// the type to satisfy the count would put the shapes and the reads in different places for no gain.
@SuppressWarnings("PMD.TooManyMethods")
public final class RouteRequirement {

    private static final RouteRequirement PERMIT_ALL =
            new RouteRequirement(Kind.PERMIT_ALL, Set.of(), Execution.PROMPT);
    /**
     * Execution is {@code PROMPT} because an abstention has no execution facet to declare; the value
     * is inert and never read by an admitted request, since an abstention never admits one.
     */
    private static final RouteRequirement ABSTAIN =
            new RouteRequirement(Kind.ABSTAIN, Set.of(), Execution.PROMPT);
    private static final RouteRequirement AUTHENTICATED =
            new RouteRequirement(Kind.AUTHENTICATED, Set.of(), Execution.PROMPT);
    // Shared too, so longRunning() on the scope-free shapes stays allocation-free on a path the
    // class contract already forbids allocating on.
    private static final RouteRequirement PERMIT_ALL_LONG_RUNNING =
            new RouteRequirement(Kind.PERMIT_ALL, Set.of(), Execution.LONG_RUNNING);
    private static final RouteRequirement AUTHENTICATED_LONG_RUNNING =
            new RouteRequirement(Kind.AUTHENTICATED, Set.of(), Execution.LONG_RUNNING);

    /** How the declared scopes are matched. */
    public enum Kind {
        /** No identity required. */
        PERMIT_ALL,
        /** Verified identity required; no scope check. */
        AUTHENTICATED,
        /** Verified identity holding at least one of the declared scopes. */
        ANY_SCOPE,
        /** Verified identity holding every declared scope. */
        ALL_SCOPES,
        /**
         * This policy does not describe the route (ADR-061 amendment A2).
         *
         * <p>Not a requirement, and deliberately not one of the answers above: {@code PERMIT_ALL}
         * describes a route as public, while this describes nothing at all. It exists so a policy
         * composed with others can decline a route instead of guessing a stance for it.
         *
         * <p>Reaching a decision point unfolded is a defect, not a permission — see
         * {@link #abstain()}.
         *
         * @since 0.12
         */
        ABSTAIN
    }

    /**
     * How a route executes, and therefore what a driver may hold across it.
     *
     * @since 0.12
     */
    public enum Execution {
        /** The handler returns promptly; request-scoped resources may be held for its duration. */
        PROMPT,
        /**
         * The handler blocks, so the route may occupy its thread for an unbounded time.
         *
         * @implNote On the Community HTTP path, a route declared this way gets no request-scoped
         *           persistence session bound, because a handler that blocks while holding a pooled
         *           connection waits on work that draws from the same pool; another driver may draw
         *           a different consequence, or none.
         */
        LONG_RUNNING
    }

    private final Kind kind;
    private final Set<String> scopes;
    private final String[] scopeArray;
    private final Execution execution;

    private RouteRequirement(Kind kind, Set<String> scopes, Execution execution) {
        this.kind = kind;
        this.scopes = scopes;
        this.execution = execution;
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
     * States that this policy does not describe the route, leaving the answer to another (ADR-061
     * amendment A2).
     *
     * <p><b>Only meaningful inside a composition.</b> {@link HttpRoutePolicy#firstDeclared} folds an
     * ordered list of policies and takes the first non-abstaining answer; abstention is how a policy
     * says "keep looking". A policy bound directly to the provider slot is still contractually total,
     * because nothing downstream will fold for it — an abstention that reaches the decision point is
     * treated as the defect it is and denies, in the same shape a {@code null} answer does.
     *
     * @return the shared abstention
     * @apiNote This is not {@link #permitAll()}. That describes a route as public; this describes
     *          nothing, and the difference is the whole point: a generated policy that answered
     *          {@code permitAll()} for routes it did not generate would silently overrule the stance
     *          the application already chose for its unmatched routes.
     * @since 0.12
     */
    public static RouteRequirement abstain() {
        return ABSTAIN;
    }

    /**
     * Whether this is an abstention rather than a requirement.
     *
     * @return {@code true} if this policy declined to describe the route
     * @since 0.12
     */
    public boolean isAbstention() {
        return kind == Kind.ABSTAIN;
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
        return new RouteRequirement(Kind.ANY_SCOPE, validated(requiredScopes), Execution.PROMPT);
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
        return new RouteRequirement(Kind.ALL_SCOPES, validated(requiredScopes), Execution.PROMPT);
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
     * Returns how this route executes.
     *
     * @return the execution shape; never {@code null}, {@link Execution#PROMPT} unless
     *         {@link #longRunning()} was called
     * @since 0.12
     */
    public Execution execution() {
        return execution;
    }

    /**
     * Returns a requirement deciding exactly as this one does, but declaring
     * {@link Execution#LONG_RUNNING}.
     *
     * @return an equal requirement whose execution is {@link Execution#LONG_RUNNING}
     * @throws IllegalStateException if this is {@link #abstain()} — an abstention declares nothing
     *                               about a route, its execution mode included, so marking one
     *                               would hand back a non-declaration the caller would read as
     *                               declaring {@link Execution#LONG_RUNNING}
     * @apiNote There is no inverse: {@link Execution#PROMPT} is what every factory already returns,
     *          so a route that wants it names nothing. Idempotent, and free on the scope-free
     *          shapes — both return shared constants rather than allocating, which is what keeps a
     *          policy that builds requirements eagerly from paying for the facet.
     * @since 0.12
     */
    public RouteRequirement longRunning() {
        if (execution == Execution.LONG_RUNNING) {
            return this;
        }
        // Switched on kind, not compared by identity: the class contract forbids identity-sensitive
        // operations on this carrier, and PERMIT_ALL / AUTHENTICATED are the only shapes whose kind
        // determines their whole value (both carry no scopes).
        return switch (kind) {
            case PERMIT_ALL -> PERMIT_ALL_LONG_RUNNING;
            case AUTHENTICATED -> AUTHENTICATED_LONG_RUNNING;
            case ANY_SCOPE, ALL_SCOPES -> new RouteRequirement(kind, scopes, Execution.LONG_RUNNING);
            // An abstention declares nothing about the route, its execution mode included. Returning
            // `this` would hand back a non-declaration the caller believes is marked long-running —
            // a plausible wrong answer, which is worse here than a loud one at composition time.
            case ABSTAIN -> throw new IllegalStateException(
                    "abstain() declares nothing about a route, so it cannot be marked LONG_RUNNING; "
                            + "declare the requirement in the policy that owns the route");
        };
    }

    /**
     * Returns how many scopes this requirement declares.
     *
     * @return the scope count; zero for {@link Kind#PERMIT_ALL} and {@link Kind#AUTHENTICATED}
     * @apiNote Paired with {@link #scopeAt(int)} so the decision path can walk the scopes without
     *          allocating an iterator, and without the carrier handing out its mutable backing
     *          array. This pair is the only read: a {@code Set}-returning accessor beside it would
     *          be a second way to the same field, and the convenient one is the one that allocates
     *          on the admission path.
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
                && execution == that.execution
                && scopes.equals(that.scopes);
    }

    @Override
    public int hashCode() {
        return Objects.hash(kind, execution, scopes);
    }

    @Override
    public String toString() {
        // PROMPT is left unrendered: it is the default, and printing it would change the rendering
        // of every requirement that existed before the facet did.
        return "RouteRequirement[" + kind
                + (scopes.isEmpty() ? "" : ", scopes=" + scopes)
                + (execution == Execution.PROMPT ? "" : ", " + execution)
                + ']';
    }
}
