/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.community.security;

import eu.exeris.kernel.community.testkit.security.TestJwt;
import eu.exeris.kernel.spi.memory.LoanedBuffer;
import eu.exeris.kernel.spi.security.ImmutablePrincipal;
import eu.exeris.kernel.spi.security.KernelIsolationClaims;
import eu.exeris.kernel.spi.security.AuthenticationResult;
import eu.exeris.kernel.spi.security.identity.ClaimsMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link ClaimsMapper} is documented as the only application-customisable point in the identity
 * pipeline, and until 0.11 this provider offered no way to supply one — the default was constructed
 * inline, so an application needing a different subject or scope shape had to reimplement
 * {@link eu.exeris.kernel.spi.security.identity.IdentityProvider} outright.
 */
@DisplayName("Community OIDC: substituting the claims mapper")
class CommunityOidcClaimsMapperSeamTest {

    private static final UUID CUSTOM_PRINCIPAL =
            UUID.fromString("11111111-2222-3333-4444-555555555555");
    private static final UUID CUSTOM_TENANT =
            UUID.fromString("66666666-7777-8888-9999-000000000000");
    private static final String CUSTOM_SCOPE = "custom:granted";

    /** Ignores the token's own subject, so its effect is unmistakable in an assertion. */
    private static final ClaimsMapper FIXED_IDENTITY = _ -> ImmutablePrincipal.ofTenant(
            CUSTOM_PRINCIPAL, CUSTOM_TENANT, Set.of(), Set.of(CUSTOM_SCOPE));

    private static CommunityOidcIdentityProvider provider() {
        return new CommunityOidcIdentityProvider(
                TestJwt.keySet(), TestJwt.EXPECTED_ISSUER, TestJwt.EXPECTED_AUDIENCE);
    }

    private static LoanedBuffer validToken() {
        return TestJwt.builder().claim("scope", "security:read").toBuffer();
    }

    private static LoanedBuffer sharedScopeToken() {
        return TestJwt.builder()
                .claim(KernelIsolationClaims.SHARED_SCOPE_KEY, "world-alpha")
                .toBuffer();
    }

    @Nested
    @DisplayName("Substitution")
    class Substitution {

        @Test
        @DisplayName("the supplied mapper decides the principal, not the built-in default")
        void suppliedMapperIsUsed() {
            try (LoanedBuffer token = validToken()) {
                AuthenticationResult result = provider().withClaimsMapper(FIXED_IDENTITY)
                        .authenticate(token);

                assertThat(result.principal().principalId())
                        .as("the mapper ignores the token subject on purpose; seeing the subject "
                                + "here would mean the default mapper ran instead")
                        .isEqualTo(CUSTOM_PRINCIPAL);
                assertThat(result.principal().scopes()).containsExactly(CUSTOM_SCOPE);
            }
        }

        @Test
        @DisplayName("a provider that was never given one still uses the Community default")
        void defaultIsUnchanged() {
            try (LoanedBuffer token = validToken()) {
                AuthenticationResult result = provider().authenticate(token);

                assertThat(result.principal().principalId())
                        .as("control: adding the seam must not change what an existing caller gets")
                        .isNotEqualTo(CUSTOM_PRINCIPAL);
                assertThat(result.principal().scopes()).containsExactly("security:read");
            }
        }

        @Test
        @DisplayName("a null mapper is refused at construction, not at the first request")
        void nullMapperIsRefused() {
            assertThatThrownBy(() -> provider().withClaimsMapper(null))
                    .as("a provider that accepted null here would fail on the authentication path, "
                            + "where the failure reads as a token problem")
                    .isInstanceOf(NullPointerException.class);
        }
    }

    @Nested
    @DisplayName("Composition with enforcingSharedScope()")
    class Composition {

        @Test
        @DisplayName("the mapper survives enforcingSharedScope() applied afterwards")
        void mapperSurvivesLaterSharedScopeEnforcement() {
            try (LoanedBuffer token = validToken()) {
                AuthenticationResult result = provider()
                        .withClaimsMapper(FIXED_IDENTITY)
                        .enforcingSharedScope()
                        .authenticate(token);

                assertThat(result.principal().principalId())
                        .as("each wither rebuilds the provider, so one that forgets to carry the "
                                + "mapper silently reverts to the default — and the caller sees a "
                                + "working provider, just not theirs")
                        .isEqualTo(CUSTOM_PRINCIPAL);
            }
        }

        @Test
        @DisplayName("shared-scope enforcement survives withClaimsMapper() applied afterwards")
        void sharedScopeEnforcementSurvivesLaterMapper() {
            try (LoanedBuffer token = sharedScopeToken()) {
                AuthenticationResult result = provider()
                        .enforcingSharedScope()
                        .withClaimsMapper(FIXED_IDENTITY)
                        .authenticate(token);

                assertThat(result.storage().sharedScopeKey())
                        .as("dropping the enforcement flag here would fail closed rather than open, "
                                + "but it would still be the wrong provider (ADR-012 §4b.7)")
                        .isNotEmpty();
            }
        }

        @Test
        @DisplayName("substituting a mapper does not enable shared scope by itself")
        void mapperDoesNotWidenIsolation() {
            try (LoanedBuffer token = sharedScopeToken()) {
                CommunityOidcIdentityProvider mapped = provider().withClaimsMapper(FIXED_IDENTITY);

                assertThatThrownBy(() -> mapped.authenticate(token))
                        .as("ADR-012 §4a routes isolation through one kernel-owned mapping; a "
                                + "customisable mapper must not become a way to widen what a token "
                                + "may reach")
                        .isInstanceOf(RuntimeException.class);
            }
        }
    }
}
