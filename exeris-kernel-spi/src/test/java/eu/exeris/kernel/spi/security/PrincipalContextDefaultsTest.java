/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.spi.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Contract tests for the {@link PrincipalContext} default methods — role/scope
 * membership and the short-circuiting {@code hasAnyRole}/{@code hasAnyScope}
 * overloads (v0.9 Sprint 4c Phase 3 SPI coverage). Exercised through a minimal
 * record implementation so only the interface defaults are under test.
 */
@DisplayName("PrincipalContext — default role/scope predicates")
class PrincipalContextDefaultsTest {

    /** Minimal SPI-only principal: supplies the abstract accessors, inherits all defaults. */
    private record TestPrincipal(Set<String> roles, Set<String> scopes) implements PrincipalContext {
        @Override
        public UUID principalId() {
            return new UUID(0L, 1L);
        }

        @Override
        public Optional<UUID> tenantId() {
            return Optional.empty();
        }
    }

    private static PrincipalContext withRoles(String... roles) {
        return new TestPrincipal(Set.of(roles), Set.of());
    }

    private static PrincipalContext withScopes(String... scopes) {
        return new TestPrincipal(Set.of(), Set.of(scopes));
    }

    @Nested
    @DisplayName("roles")
    class Roles {

        @Test
        @DisplayName("hasRole reflects membership")
        void hasRole() {
            PrincipalContext p = withRoles("ADMIN");
            assertThat(p.hasRole("ADMIN")).isTrue();
            assertThat(p.hasRole("USER")).isFalse();
        }

        @Test
        @DisplayName("hasAnyRole(varargs) true on first match, false when none match or empty")
        void hasAnyRoleVarargs() {
            PrincipalContext p = withRoles("A", "B");
            assertThat(p.hasAnyRole("X", "B")).isTrue();
            assertThat(p.hasAnyRole("X", "Y")).isFalse();
            assertThat(p.hasAnyRole()).isFalse();
        }

        @Test
        @DisplayName("hasAnyRole single-arg")
        void hasAnyRoleSingle() {
            PrincipalContext p = withRoles("A");
            assertThat(p.hasAnyRole("A")).isTrue();
            assertThat(p.hasAnyRole("Z")).isFalse();
        }

        @Test
        @DisplayName("hasAnyRole two-arg short-circuits on either")
        void hasAnyRoleTwo() {
            PrincipalContext p = withRoles("B");
            assertThat(p.hasAnyRole("B", "C")).isTrue();
            assertThat(p.hasAnyRole("C", "B")).isTrue();
            assertThat(p.hasAnyRole("C", "D")).isFalse();
        }

        @Test
        @DisplayName("hasAnyRole three-arg matches any of the three")
        void hasAnyRoleThree() {
            PrincipalContext p = withRoles("C");
            assertThat(p.hasAnyRole("A", "B", "C")).isTrue();
            assertThat(p.hasAnyRole("C", "B", "A")).isTrue();
            assertThat(p.hasAnyRole("X", "Y", "Z")).isFalse();
        }

        @Test
        @DisplayName("roleMask defaults to 0 for principals that don't pack one")
        void roleMaskDefaultsZero() {
            assertThat(withRoles("A").roleMask()).isZero();
        }
    }

    @Nested
    @DisplayName("scopes")
    class Scopes {

        @Test
        @DisplayName("hasScope reflects membership")
        void hasScope() {
            PrincipalContext p = withScopes("read");
            assertThat(p.hasScope("read")).isTrue();
            assertThat(p.hasScope("write")).isFalse();
        }

        @Test
        @DisplayName("hasAnyScope(varargs) true on match, false when none or empty")
        void hasAnyScopeVarargs() {
            PrincipalContext p = withScopes("read", "list");
            assertThat(p.hasAnyScope("write", "list")).isTrue();
            assertThat(p.hasAnyScope("write", "delete")).isFalse();
            assertThat(p.hasAnyScope()).isFalse();
        }

        @Test
        @DisplayName("hasAnyScope single/two/three-arg overloads")
        void hasAnyScopeFixedArity() {
            PrincipalContext p = withScopes("read");
            assertThat(p.hasAnyScope("read")).isTrue();
            assertThat(p.hasAnyScope("x")).isFalse();
            assertThat(p.hasAnyScope("read", "x")).isTrue();
            assertThat(p.hasAnyScope("x", "read")).isTrue();
            assertThat(p.hasAnyScope("x", "y")).isFalse();
            assertThat(p.hasAnyScope("x", "y", "read")).isTrue();
            assertThat(p.hasAnyScope("x", "y", "z")).isFalse();
        }
    }
}
