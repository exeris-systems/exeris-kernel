/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.spi.http;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("RouteRequirement — the execution facet (ADR-077)")
class RouteRequirementTest {

    private static final String READ = "orders:read";
    private static final String WRITE = "orders:write";

    @Nested
    @DisplayName("The default is what shipped before the facet existed")
    class Default {

        @Test
        @DisplayName("every factory returns PROMPT")
        void everyFactoryIsPrompt() {
            assertThat(RouteRequirement.permitAll().execution())
                    .isEqualTo(RouteRequirement.Execution.PROMPT);
            assertThat(RouteRequirement.authenticated().execution())
                    .isEqualTo(RouteRequirement.Execution.PROMPT);
            assertThat(RouteRequirement.requiringAnyScope(Set.of(READ)).execution())
                    .isEqualTo(RouteRequirement.Execution.PROMPT);
            assertThat(RouteRequirement.requiringAllScopes(Set.of(READ, WRITE)).execution())
                    .isEqualTo(RouteRequirement.Execution.PROMPT);
        }

        @Test
        @DisplayName("PROMPT stays out of toString, so no existing rendering moved")
        void promptIsNotRendered() {
            assertThat(RouteRequirement.authenticated().toString())
                    .isEqualTo("RouteRequirement[AUTHENTICATED]");
            assertThat(RouteRequirement.requiringAnyScope(Set.of(READ)).toString())
                    .isEqualTo("RouteRequirement[ANY_SCOPE, scopes=[" + READ + "]]");
        }
    }

    @Nested
    @DisplayName("longRunning()")
    class LongRunning {

        @Test
        @DisplayName("flips execution and carries kind and scopes across unchanged")
        void carriesTheRestAcross() {
            RouteRequirement base = RouteRequirement.requiringAllScopes(Set.of(READ, WRITE));
            RouteRequirement blocking = base.longRunning();

            assertThat(blocking.execution()).isEqualTo(RouteRequirement.Execution.LONG_RUNNING);
            assertThat(blocking.kind()).isEqualTo(base.kind());
            assertThat(blocking.scopeCount()).isEqualTo(base.scopeCount());
            assertThat(scopesOf(blocking)).isEqualTo(scopesOf(base));
        }

        @Test
        @DisplayName("is idempotent")
        void idempotent() {
            RouteRequirement once = RouteRequirement.authenticated().longRunning();
            assertThat(once.longRunning()).isEqualTo(once);
            assertThat(once.longRunning().execution())
                    .isEqualTo(RouteRequirement.Execution.LONG_RUNNING);
        }

        @Test
        @DisplayName("the scope-free shapes hand back shared instances, per the allocation contract")
        void scopeFreeShapesAreShared() {
            // An identity assertion is the point here, not an oversight: the class contract forbids a
            // policy from allocating a requirement per request, so longRunning() on the two shapes
            // that carry no scopes has to be free. Identity is banned in production code on this
            // carrier; asserting the sharing it promises is what a test is for.
            assertThat(RouteRequirement.permitAll().longRunning())
                    .isSameAs(RouteRequirement.permitAll().longRunning());
            assertThat(RouteRequirement.authenticated().longRunning())
                    .isSameAs(RouteRequirement.authenticated().longRunning());
        }

        @Test
        @DisplayName("renders in toString, since it is not the default")
        void rendered() {
            assertThat(RouteRequirement.authenticated().longRunning().toString())
                    .isEqualTo("RouteRequirement[AUTHENTICATED, LONG_RUNNING]");
        }
    }

    @Nested
    @DisplayName("Value semantics include the facet")
    class ValueSemantics {

        @Test
        @DisplayName("two requirements differing only in execution are not equal")
        void executionParticipatesInEquality() {
            RouteRequirement prompt = RouteRequirement.requiringAnyScope(Set.of(READ));
            RouteRequirement blocking = prompt.longRunning();

            assertThat(blocking).isNotEqualTo(prompt);
            assertThat(prompt).isNotEqualTo(blocking);
            assertThat(blocking.hashCode()).isNotEqualTo(prompt.hashCode());
        }

        @Test
        @DisplayName("equality still holds across independently built equal requirements")
        void equalityIsStillByValue() {
            assertThat(RouteRequirement.requiringAllScopes(Set.of(READ, WRITE)).longRunning())
                    .isEqualTo(RouteRequirement.requiringAllScopes(Set.of(WRITE, READ)).longRunning());
        }
    }

    private static Set<String> scopesOf(RouteRequirement requirement) {
        return java.util.stream.IntStream.range(0, requirement.scopeCount())
                .mapToObj(requirement::scopeAt)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }
}
