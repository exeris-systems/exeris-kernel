/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.tck.contract.http;

import eu.exeris.kernel.spi.http.HttpMethod;
import eu.exeris.kernel.spi.http.HttpRoutePolicy;
import eu.exeris.kernel.spi.http.RouteRequirement;
import eu.exeris.kernel.spi.security.ImmutablePrincipal;
import eu.exeris.kernel.spi.security.PrincipalContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * TCK: contract for per-route authorization decisions (ADR-061).
 *
 * <h2>What this suite is built to catch</h2>
 * <p>A route-authorization implementation that admits everything passes any suite that only checks
 * admission. So the mandatory cases here are the ones where the answer is <em>no</em>: the denials, and
 * the route the policy never described. Every admit case below is paired with the denial that proves
 * the admit was a decision rather than a default — a binding that returns {@code ADMIT} unconditionally
 * fails this suite on its first denial case, which is the point.
 *
 * <h2>Contract</h2>
 * <ul>
 *   <li>{@code PERMIT_ALL} admits with no identity — and is the <em>only</em> requirement that does.</li>
 *   <li>Every other requirement denies as {@code UNAUTHENTICATED} when no identity is present. That
 *       distinction is load-bearing: the caller maps it to {@code 401}, not {@code 403}.</li>
 *   <li>{@code AUTHENTICATED} admits any verified identity, whatever scopes it carries.</li>
 *   <li>{@code ANY_SCOPE} admits on intersection, denies as {@code FORBIDDEN} on none.</li>
 *   <li>{@code ALL_SCOPES} admits only on full coverage; a partial match is {@code FORBIDDEN}.</li>
 *   <li>An identity that lacks the scope is {@code FORBIDDEN}, never {@code UNAUTHENTICATED} —
 *       telling an authenticated caller to authenticate again is a contract violation, not a nuance.</li>
 *   <li>A policy returning {@code null} denies rather than propagating a failure into the transport.</li>
 * </ul>
 *
 * <h2>How to use</h2>
 * <pre>{@code
 * class CommunityHttpRoutePolicyTckTest extends AbstractHttpRoutePolicyTck {
 *     @Override
 *     protected Decision decide(RouteRequirement requirement, PrincipalContext principal) {
 *         return switch (RouteAuthorizationEnforcer.decide(requirement, principal)) {
 *             case ADMIT -> Decision.ADMIT;
 *             case UNAUTHENTICATED -> Decision.UNAUTHENTICATED;
 *             case FORBIDDEN -> Decision.FORBIDDEN;
 *         };
 *     }
 * }
 * }</pre>
 *
 * @since 0.11.0
 */
@DisplayName("TCK: HTTP route-authorization policy (ADR-061)")
public abstract class AbstractHttpRoutePolicyTck {

    /** Binding-neutral mirror of the decision outcome, so the TCK does not depend on Core. */
    public enum Decision {
        /** Request may proceed. */
        ADMIT,
        /** No verified identity — maps to {@code 401}. */
        UNAUTHENTICATED,
        /** Verified identity lacking the declared scopes — maps to {@code 403}. */
        FORBIDDEN
    }

    private static final String READ = "orders:read";
    private static final String WRITE = "orders:write";
    private static final String UNRELATED = "billing:read";

    /**
     * Decides one request against one requirement, using the binding under test.
     *
     * @param requirement what the route declares
     * @param principal   the verified identity, or {@code null} when none was established
     * @return the decision
     */
    protected abstract Decision decide(RouteRequirement requirement, PrincipalContext principal);

    /**
     * Resolves a requirement through a policy and decides, using the binding under test.
     *
     * @param policy    the policy to consult
     * @param method    the request method
     * @param path      the request path
     * @param principal the verified identity, or {@code null}
     * @return the decision
     */
    protected abstract Decision decide(HttpRoutePolicy policy,
                                       HttpMethod method,
                                       String path,
                                       PrincipalContext principal);

    @Nested
    @DisplayName("Denials — the cases an admit-everything implementation fails")
    class Denials {

        @Test
        @DisplayName("no identity is UNAUTHENTICATED, not FORBIDDEN, for every non-public requirement")
        void noIdentityIsUnauthenticated() {
            assertThat(decide(RouteRequirement.authenticated(), null))
                    .as("the caller maps this to 401; returning FORBIDDEN would send a 403 to someone "
                            + "who has not been asked to authenticate yet")
                    .isEqualTo(Decision.UNAUTHENTICATED);
            assertThat(decide(RouteRequirement.requiringAnyScope(Set.of(READ)), null))
                    .isEqualTo(Decision.UNAUTHENTICATED);
            assertThat(decide(RouteRequirement.requiringAllScopes(Set.of(READ, WRITE)), null))
                    .isEqualTo(Decision.UNAUTHENTICATED);
        }

        @Test
        @DisplayName("an identity without any required scope is FORBIDDEN, not UNAUTHENTICATED")
        void wrongScopeIsForbidden() {
            PrincipalContext principal = principalWith(UNRELATED);

            assertThat(decide(RouteRequirement.requiringAnyScope(Set.of(READ, WRITE)), principal))
                    .as("the identity is verified; telling it to authenticate again is a contract "
                            + "violation and an infinite retry loop for a well-behaved client")
                    .isEqualTo(Decision.FORBIDDEN);
        }

        @Test
        @DisplayName("ALL_SCOPES denies a partial match")
        void partialMatchIsForbiddenUnderAllScopes() {
            PrincipalContext principal = principalWith(READ);

            assertThat(decide(RouteRequirement.requiringAllScopes(Set.of(READ, WRITE)), principal))
                    .as("holding one of two required scopes is not holding both — an implementation "
                            + "that reads ALL_SCOPES as ANY_SCOPE passes every other case in this suite")
                    .isEqualTo(Decision.FORBIDDEN);
        }

        @Test
        @DisplayName("a principal carrying no scopes at all is denied by any scope requirement")
        void scopelessPrincipalIsForbidden() {
            PrincipalContext principal = principalWith();

            assertThat(decide(RouteRequirement.requiringAnyScope(Set.of(READ)), principal))
                    .isEqualTo(Decision.FORBIDDEN);
            assertThat(decide(RouteRequirement.requiringAllScopes(Set.of(READ)), principal))
                    .isEqualTo(Decision.FORBIDDEN);
        }
    }

    @Nested
    @DisplayName("Admissions — paired with the denial that proves each is a decision")
    class Admissions {

        @Test
        @DisplayName("PERMIT_ALL admits without identity, and is the only requirement that does")
        void permitAllAdmitsAnonymously() {
            assertThat(decide(RouteRequirement.permitAll(), null)).isEqualTo(Decision.ADMIT);
            assertThat(decide(RouteRequirement.authenticated(), null))
                    .as("the control: if this also admitted, the case above would prove nothing")
                    .isEqualTo(Decision.UNAUTHENTICATED);
        }

        @Test
        @DisplayName("AUTHENTICATED admits any verified identity regardless of scopes")
        void authenticatedIgnoresScopes() {
            assertThat(decide(RouteRequirement.authenticated(), principalWith())).isEqualTo(Decision.ADMIT);
            assertThat(decide(RouteRequirement.authenticated(), principalWith(UNRELATED)))
                    .isEqualTo(Decision.ADMIT);
        }

        @Test
        @DisplayName("ANY_SCOPE admits on intersection")
        void anyScopeAdmitsOnIntersection() {
            assertThat(decide(RouteRequirement.requiringAnyScope(Set.of(READ, WRITE)), principalWith(WRITE)))
                    .isEqualTo(Decision.ADMIT);
        }

        @Test
        @DisplayName("ALL_SCOPES admits on full coverage, and extra scopes do not interfere")
        void allScopesAdmitsOnFullCoverage() {
            assertThat(decide(RouteRequirement.requiringAllScopes(Set.of(READ, WRITE)),
                    principalWith(READ, WRITE, UNRELATED)))
                    .isEqualTo(Decision.ADMIT);
        }
    }

    @Nested
    @DisplayName("The execution facet (ADR-077) is orthogonal to the authorization decision")
    class ExecutionFacet {

        @Test
        @DisplayName("declaring LONG_RUNNING changes no decision, on any shape or any principal")
        void longRunningDoesNotMoveTheDecision() {
            // The facet describes what a driver may hold across the handler, not who may call it. An
            // implementation that keyed its decision on the whole requirement — by equality against a
            // constant, say — would pass every other case in this TCK and fail here.
            List<RouteRequirement> shapes = List.of(
                    RouteRequirement.permitAll(),
                    RouteRequirement.authenticated(),
                    RouteRequirement.requiringAnyScope(Set.of(READ, WRITE)),
                    RouteRequirement.requiringAllScopes(Set.of(READ, WRITE)));
            List<PrincipalContext> principals = new ArrayList<>();
            principals.add(null);
            principals.add(principalWith());
            principals.add(principalWith(READ));
            principals.add(principalWith(READ, WRITE, UNRELATED));

            assertThat(shapes).hasSize(4);
            for (RouteRequirement shape : shapes) {
                for (PrincipalContext principal : principals) {
                    assertThat(decide(shape.longRunning(), principal))
                            .as("%s under %s must decide as its PROMPT twin",
                                    shape, principal == null ? "no identity" : "an identity")
                            .isEqualTo(decide(shape, principal));
                }
            }
        }

        @Test
        @DisplayName("the decision that proves the case above is not vacuous")
        void theShapesUnderTestStillDisagreeWithEachOther() {
            // Without this, an implementation that returned one constant for everything would satisfy
            // longRunningDoesNotMoveTheDecision perfectly.
            assertThat(decide(RouteRequirement.permitAll().longRunning(), null))
                    .isEqualTo(Decision.ADMIT);
            assertThat(decide(RouteRequirement.authenticated().longRunning(), null))
                    .isEqualTo(Decision.UNAUTHENTICATED);
            assertThat(decide(RouteRequirement.requiringAllScopes(Set.of(READ, WRITE)).longRunning(),
                    principalWith(READ)))
                    .isEqualTo(Decision.FORBIDDEN);
        }
    }

    @Nested
    @DisplayName("The route the policy never described")
    class UnmatchedRoute {

        @Test
        @DisplayName("a policy's answer for an undeclared route is honoured, whichever way it decides")
        void undeclaredRouteFollowsThePolicy() {
            HttpRoutePolicy failClosed = (method, path) ->
                    "/api/orders".equals(path) ? RouteRequirement.permitAll() : HttpRoutePolicy.unmatched();

            assertThat(decide(failClosed, HttpMethod.GET, "/api/orders", null))
                    .isEqualTo(Decision.ADMIT);
            assertThat(decide(failClosed, HttpMethod.GET, "/api/internal", null))
                    .as("the undeclared route is the whole security posture; a kernel that silently "
                            + "admitted it would make every declared rule decorative")
                    .isEqualTo(Decision.UNAUTHENTICATED);
        }

        @Test
        @DisplayName("HttpRoutePolicy.unmatched() is fail-closed")
        void unmatchedDefaultIsFailClosed() {
            assertThat(decide(HttpRoutePolicy.unmatched(), null))
                    .as("the contract names this the answer to prefer when unsure; if it admitted, the "
                            + "advice would be actively harmful")
                    .isEqualTo(Decision.UNAUTHENTICATED);
        }

        @Test
        @DisplayName("a policy returning null denies outright — it is a defect, not an unmatched route")
        void nullAnswerDenies() {
            HttpRoutePolicy broken = (method, path) -> null;

            assertThat(decide(broken, HttpMethod.GET, "/anything", null))
                    .as("a policy bug must cost one request a 401, not crash a reactor thread")
                    .isEqualTo(Decision.UNAUTHENTICATED);
            assertThat(decide(broken, HttpMethod.GET, "/anything", principalWith(READ)))
                    .as("and it must NOT be read as unmatched(), which is authenticated-only: that "
                            + "would let a broken policy admit any logged-in caller to a route that "
                            + "may have demanded an admin scope. A broken policy grants nothing")
                    .isEqualTo(Decision.FORBIDDEN);
        }
    }

    @Nested
    @DisplayName("Requirement carrier")
    class Carrier {

        @Test
        @DisplayName("an empty scope set is rejected at construction, not silently reinterpreted")
        void emptyScopeSetIsRejected() {
            assertThatThrownBy(() -> RouteRequirement.requiringAnyScope(Set.of()))
                    .as("an empty any-of can never be satisfied — accepting it would create a route "
                            + "nobody can call, and the failure would surface as a mystery 403")
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> RouteRequirement.requiringAllScopes(Set.of()))
                    .as("an empty all-of is vacuously true — accepting it would silently downgrade the "
                            + "route to authenticated-only")
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("equality is by value, so shared constants are not the only way to compare")
        void equalityIsByValue() {
            assertThat(RouteRequirement.requiringAnyScope(Set.of(READ)))
                    .isEqualTo(RouteRequirement.requiringAnyScope(Set.of(READ)))
                    .isNotEqualTo(RouteRequirement.requiringAllScopes(Set.of(READ)));
        }

        @Test
        @DisplayName("scopeCount/scopeAt expose exactly the declared scopes")
        void scopeAccessorsMatchTheDeclaredSet() {
            RouteRequirement requirement = RouteRequirement.requiringAllScopes(Set.of(READ, WRITE));

            assertThat(requirement.scopeCount()).isEqualTo(2);
            assertThat(java.util.stream.IntStream.range(0, requirement.scopeCount())
                    .mapToObj(requirement::scopeAt))
                    .containsExactlyInAnyOrder(READ, WRITE);
        }
    }

    private static PrincipalContext principalWith(String... scopes) {
        return ImmutablePrincipal.ofScopes(UUID.randomUUID(), Set.of(scopes));
    }
}
