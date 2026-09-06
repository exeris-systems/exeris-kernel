/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.security;

import eu.exeris.kernel.spi.exceptions.security.SecurityAuthenticationException;
import eu.exeris.kernel.spi.memory.LoanedBuffer;
import eu.exeris.kernel.spi.security.AuthenticationResult;
import eu.exeris.kernel.spi.security.ImmutableStorageContext;
import eu.exeris.kernel.spi.security.SecurityProvider;
import eu.exeris.kernel.spi.security.StorageContext;
import eu.exeris.kernel.spi.security.identity.IdentityProvider;
import eu.exeris.kernel.spi.security.identity.IdentityProviderRegistry;

import java.security.interfaces.RSAPublicKey;
import java.util.List;
import java.util.Map;

/**
 * Community {@link SecurityProvider} — a thin dispatcher over an {@link IdentityProviderRegistry}
 * (ADR-040, Option S-A).
 *
 * <p>It selects exactly one {@link IdentityProvider} for the incoming token and delegates
 * validation. The selected provider's failure is terminal — there is no fallback to another
 * provider (fail-closed; re-dispatch on failure would be token-confusion). When no provider claims
 * the token the dispatcher denies fail-closed with {@code EX-SEC-2002}.
 *
 * <p>The JWT verification pipeline lives in {@link CommunityOidcIdentityProvider} and
 * {@link CommunityOidcTokenValidator}.
 *
 * <p>ServiceLoader-based discovery of {@link IdentityProvider} bindings and config-driven
 * construction (issuer / audience / JWKS endpoint) land with the config wiring step; today the
 * registry is built from the directly-constructed Community OIDC provider.
 *
 * @since 0.5
 */
public final class CommunitySecurityProvider implements SecurityProvider {

    private static final String PROVIDER_ID = "jwt-community";
    private static final String PROVIDER_NAME = "ExerisCommunity/JWT";
    private static final String EXPECTED_ISSUER = "https://auth.example.com";
    private static final String EXPECTED_AUDIENCE = "exeris-kernel";
    private static final String JWT_TYPE = "JWT";

    private final IdentityProviderRegistry registry;
    private final IdentityProvider identityProvider;

    /**
     * Public no-arg constructor required by {@link java.util.ServiceLoader}.
     *
     * @throws IllegalStateException if more than one
     *         {@link eu.exeris.kernel.spi.security.identity.ClaimsMapper} is registered on the
     *         classpath
     */
    public CommunitySecurityProvider() {
        this(Map.of(), EXPECTED_ISSUER, EXPECTED_AUDIENCE);
    }

    /**
     * Creates a provider that verifies tokens against a fixed key-set map, mapping claims with
     * the {@link eu.exeris.kernel.spi.security.identity.ClaimsMapper} registered via
     * {@link CommunityClaimsMapperResolver} (the Community default when none is registered).
     *
     * @param keysByKid a snapshot of verification keys ({@code kid} to RSA public key)
     * @param expectedIssuer the {@code iss} claim value a token must present to be accepted
     * @param expectedAudience the {@code aud} claim value a token must present to be accepted
     * @throws IllegalStateException if more than one
     *         {@link eu.exeris.kernel.spi.security.identity.ClaimsMapper} is registered on the
     *         classpath
     */
    public CommunitySecurityProvider(Map<String, RSAPublicKey> keysByKid,
                                     String expectedIssuer,
                                     String expectedAudience) {
        this(new CommunityOidcIdentityProvider(keysByKid, expectedIssuer, expectedAudience)
                .withClaimsMapper(CommunityClaimsMapperResolver.resolve()));
    }

    private CommunitySecurityProvider(IdentityProvider identityProvider) {
        this.identityProvider = identityProvider;
        this.registry = IdentityProviderRegistry.of(List.of(identityProvider));
    }

    /**
     * Opt-in factory wiring a rotating key set. Rotation is applied through the injected
     * {@link JwksKeyResolver} (typically a {@link CommunityRotatingKeySet}); the verify pipeline is
     * unchanged.
     */
    /* default */ static CommunitySecurityProvider withKeyResolver(
            JwksKeyResolver keyResolver, String expectedIssuer, String expectedAudience) {
        return new CommunitySecurityProvider(
                new CommunityOidcIdentityProvider(keyResolver, expectedIssuer, expectedAudience)
                        .withClaimsMapper(CommunityClaimsMapperResolver.resolve()));
    }

    /**
     * Package-private: lets the wiring test assert that this provider was assembled through
     * {@link CommunityClaimsMapperResolver}, so a regression that stops routing through the
     * resolver fails a test instead of silently reverting to the Community default mapper.
     */
    /* default */ IdentityProvider identityProvider() {
        return identityProvider;
    }

    /**
     * {@inheritDoc}
     *
     * @implNote Always {@code "jwt-community"}.
     */
    @Override
    public String providerId() {
        return PROVIDER_ID;
    }

    /**
     * {@inheritDoc}
     *
     * @implNote Always {@code "ExerisCommunity/JWT"}.
     */
    @Override
    public String providerName() {
        return PROVIDER_NAME;
    }

    /**
     * {@inheritDoc}
     *
     * @implNote Always {@code 0}, the Community-tier priority.
     */
    @Override
    public int priority() {
        return 0;
    }

    /**
     * {@inheritDoc}
     *
     * @throws SecurityAuthenticationException ({@code EX-SEC-2002}) if {@code rawToken} is
     *         {@code null}, if no registered {@link IdentityProvider} claims the token, or if
     *         the selected provider denies validation
     * @implNote Delegates to the single {@link IdentityProvider} this instance's
     *           {@link eu.exeris.kernel.spi.security.identity.IdentityProviderRegistry} selects
     *           for {@code rawToken}; there is no fallback to a second provider on denial.
     */
    @Override
    public AuthenticationResult authenticate(LoanedBuffer rawToken) {
        if (rawToken == null) {
            throw new SecurityAuthenticationException(JWT_TYPE, "missing-token");
        }
        IdentityProvider provider = registry.select(rawToken);
        if (provider == null) {
            // No provider claims the token → terminal deny, never fail-open.
            throw new SecurityAuthenticationException(JWT_TYPE, "no-identity-provider");
        }
        return provider.authenticate(rawToken);
    }

    /**
     * {@inheritDoc}
     *
     * @implNote Returns {@link ImmutableStorageContext#GLOBAL} — a {@code SHARED}-strategy
     *           context with no isolation key, so it carries no tenant scoping.
     */
    @Override
    public StorageContext systemStorageContext() {
        return ImmutableStorageContext.GLOBAL;
    }
}
