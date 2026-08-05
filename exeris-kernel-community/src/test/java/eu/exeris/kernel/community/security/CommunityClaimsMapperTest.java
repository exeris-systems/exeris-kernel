/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.community.security;

import eu.exeris.kernel.spi.exceptions.security.SecurityAuthenticationException;
import eu.exeris.kernel.spi.security.PrincipalContext;
import eu.exeris.kernel.spi.security.identity.VerifiedClaims;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The mapper decides what an authenticated caller is allowed to do, so every case here is about the
 * difference between what the token said and what the principal ends up holding.
 *
 * <p>The load-bearing one is {@link Absence}: until 0.11 this mapper handed out {@code security:read}
 * to everyone regardless of the token, which made {@code security:write} unreachable and every route
 * requirement indistinguishable from no requirement. A test suite that only checks the present-claim
 * cases would pass just as happily against that old behaviour.
 */
@DisplayName("CommunityClaimsMapper")
class CommunityClaimsMapperTest {

    private static final UUID SUBJECT = UUID.fromString("0193a1b2-c3d4-7e5f-8a9b-0c1d2e3f4a5b");

    private final CommunityClaimsMapper mapper = new CommunityClaimsMapper();

    @Nested
    @DisplayName("Scopes come from the token")
    class Scopes {

        @Test
        @DisplayName("the OAuth2 `scope` claim is split on whitespace")
        void spaceDelimitedScopeClaim() {
            PrincipalContext principal = mapper.map(claims(
                    Map.of("scope", "orders:read  orders:write\tbilling:read"), Map.of()));

            assertThat(principal.scopes())
                    .as("RFC 6749 §3.3 makes this one string, not a list; a mapper treating it as a "
                            + "single opaque value grants one scope nobody will ever ask for")
                    .containsExactlyInAnyOrder("orders:read", "orders:write", "billing:read");
        }

        @Test
        @DisplayName("the array-form `scp` claim is read too, and unioned with `scope`")
        void arrayScopeClaimIsUnioned() {
            PrincipalContext principal = mapper.map(claims(
                    Map.of("scope", "orders:read"),
                    Map.of("scp", Set.of("billing:read"))));

            assertThat(principal.scopes()).containsExactlyInAnyOrder("orders:read", "billing:read");
        }

        @Test
        @DisplayName("roles reach the principal, so the role mask stops being permanently zero")
        void rolesArePropagated() {
            PrincipalContext principal = mapper.map(claims(
                    Map.of(), Map.of("roles", Set.of("ROLE_ADMIN", "ROLE_USER"))));

            assertThat(principal.roles()).containsExactlyInAnyOrder("ROLE_ADMIN", "ROLE_USER");
        }
    }

    @Nested
    @DisplayName("Absence grants nothing")
    class Absence {

        @Test
        @DisplayName("a token declaring no scopes yields none — no default is substituted")
        void noScopeClaimYieldsNoScopes() {
            PrincipalContext principal = mapper.map(claims(Map.of(), Map.of()));

            assertThat(principal.scopes())
                    .as("the whole point of the change: a default grant would let a route requiring "
                            + "security:read admit a caller who never asked for it, which is the same "
                            + "as the route having no requirement at all")
                    .isEmpty();
            assertThat(principal.hasScope("security:read"))
                    .as("and specifically not the scope this mapper used to hand out unconditionally")
                    .isFalse();
            assertThat(principal.roles()).isEmpty();
        }

        @Test
        @DisplayName("a blank `scope` claim is absence, not a scope named \"\"")
        void blankScopeClaimYieldsNoScopes() {
            PrincipalContext principal = mapper.map(claims(Map.of("scope", "   "), Map.of()));

            assertThat(principal.scopes()).isEmpty();
        }
    }

    @Nested
    @DisplayName("Subject handling is unchanged")
    class Subject {

        @Test
        @DisplayName("sub becomes both principalId and tenantId")
        void subjectBecomesPrincipalAndTenant() {
            PrincipalContext principal = mapper.map(claims(Map.of(), Map.of()));

            assertThat(principal.principalId()).isEqualTo(SUBJECT);
            assertThat(principal.tenantId()).contains(SUBJECT);
        }

        @Test
        @DisplayName("a non-UUID subject is rejected")
        void nonUuidSubjectRejected() {
            assertThatThrownBy(() -> mapper.map(new StubClaims("not-a-uuid", Map.of(), Map.of())))
                    .isInstanceOf(SecurityAuthenticationException.class);
        }
    }

    private static VerifiedClaims claims(Map<String, String> single, Map<String, Set<String>> multi) {
        return new StubClaims(SUBJECT.toString(), single, multi);
    }

    /** Minimal in-test {@link VerifiedClaims}; the real one is the validator's output, not ours. */
    private record StubClaims(String subject,
                              Map<String, String> single,
                              Map<String, Set<String>> multi) implements VerifiedClaims {

        @Override
        public String issuer() {
            return "https://auth.example.com";
        }

        @Override
        public Set<String> audience() {
            return Set.of("exeris-kernel");
        }

        @Override
        public Optional<Instant> expiresAt() {
            return Optional.of(Instant.now().plusSeconds(60L));
        }

        @Override
        public Optional<String> claim(String name) {
            return Optional.ofNullable(single.get(name));
        }

        @Override
        public Set<String> stringSetClaim(String name) {
            return multi.getOrDefault(name, Set.of());
        }
    }
}
