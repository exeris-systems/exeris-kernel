/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.spi.http;

import java.util.List;
import java.util.Objects;
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
     * <p><b>Before choosing fail-closed, account for the routes you did not write.</b> A deployment
     * that binds a policy but no {@code HTTP_SERVER_HANDLER} gets the driver's built-in liveness,
     * readiness and database-probe endpoints — and those are routes like any other, so a fail-closed
     * unmatched answer denies them. An orchestrator's probes then fail against a healthy process.
     * Declare them {@link RouteRequirement#permitAll()} explicitly. The kernel does not exempt them
     * for you: a driver-local notion of "public" would be a second answer to the question this
     * contract now owns, and the first thing it would do is disagree with yours.
     *
     * @return the fail-closed answer, which is the one to prefer when unsure
     */
    static RouteRequirement unmatched() {
        return RouteRequirement.authenticated();
    }

    /**
     * Folds an ordered list of policies, taking the first that declares a requirement for the route
     * (ADR-061 amendment A2).
     *
     * <h2>Why the fallback has no default</h2>
     * <p>{@code whenNoneDeclares} is a required argument because it is the fail-open/fail-closed
     * choice {@link #unmatched()} names, and the whole reason abstention exists is that a second
     * author must not answer it. A default here would re-create the problem it solves: a composed
     * policy silently overruling the stance the application already chose.
     *
     * <h2>What each policy owes</h2>
     * <p>Inside a composition a policy is total over <em>its own</em> routes and abstains elsewhere;
     * the composition is what is total. A policy bound directly to the provider slot is still total
     * on its own, because nothing will fold for it — an abstention reaching the decision point is a
     * defect and denies.
     *
     * <p>Order is the resolution rule and therefore part of the application's declaration: with two
     * policies claiming one route, the earlier wins and the later never runs. Composing a generated
     * policy after a hand-written one is how an application keeps the last word on a route it wrote.
     *
     * <p>A {@code null} from a composed policy stays a defect and ends the fold — it is not read as a
     * quieter abstention, which would let a broken policy hand its routes to the next one instead of
     * denying them.
     *
     * <p>Allocation: the list is copied once here, so a request-time call allocates nothing.
     *
     * @param policies         the policies to consult, in order; must be non-null with no null element
     * @param whenNoneDeclares the answer when every policy abstains — the application's unmatched
     *                         stance, stated once; must be non-null and not itself an abstention
     * @return a policy answering the first declared requirement, else {@code whenNoneDeclares}
     * @throws IllegalArgumentException if {@code whenNoneDeclares} is an abstention
     * @throws NullPointerException     if any argument or element is {@code null}
     * @since 0.12.0
     */
    // Scope: composition time, not a runtime path. A policy list is folded once when the application
    // declares it, so the concatenated messages below are outside the rawArgs[] discipline that
    // governs exception construction on runtime failure paths — the fold's own returned lambda, which
    // is the part that runs per request, builds no message and throws nothing.
    static HttpRoutePolicy firstDeclared(List<HttpRoutePolicy> policies,
                                         RouteRequirement whenNoneDeclares) {
        Objects.requireNonNull(policies, "policies");
        Objects.requireNonNull(whenNoneDeclares, "whenNoneDeclares");
        if (whenNoneDeclares.isAbstention()) {
            throw new IllegalArgumentException(
                    "whenNoneDeclares must be a requirement, not an abstention — a composition that "
                            + "answers abstain() denies every undeclared route as a policy defect");
        }
        HttpRoutePolicy[] ordered = policies.toArray(new HttpRoutePolicy[0]);
        for (HttpRoutePolicy policy : ordered) {
            Objects.requireNonNull(policy, "policies element");
        }
        return (method, path) -> {
            for (HttpRoutePolicy policy : ordered) {
                RouteRequirement answer = policy.requirementFor(method, path);
                if (answer == null) {
                    return null;
                }
                if (!answer.isAbstention()) {
                    return answer;
                }
            }
            return whenNoneDeclares;
        };
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
