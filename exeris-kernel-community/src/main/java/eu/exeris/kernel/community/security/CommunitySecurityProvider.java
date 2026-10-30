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
import eu.exeris.kernel.spi.security.identity.ClaimsMapper;
import eu.exeris.kernel.spi.security.identity.IdentityProvider;
import eu.exeris.kernel.spi.security.identity.IdentityProviderRegistry;
import eu.exeris.kernel.spi.security.identity.KeyRotationPolicy;

import java.net.URI;
import java.security.interfaces.RSAPublicKey;
import java.time.Clock;
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
 * <p>The registry holds exactly one Community OIDC provider. The {@code ServiceLoader} instance holds
 * no verification key, so it denies every token; the Community security subsystem replaces it with
 * one built by {@link #overJwksEndpoint} when {@code security.jwks.uri}, {@code security.jwt.issuer}
 * and {@code security.jwt.audience} are configured. {@code ServiceLoader} discovery of
 * {@link IdentityProvider} bindings is not part of this provider.
 *
 * @since 0.5
 */
public final class CommunitySecurityProvider implements SecurityProvider, AutoCloseable {

    private static final String PROVIDER_ID = "jwt-community";
    private static final String PROVIDER_NAME = "ExerisCommunity/JWT";
    private static final String EXPECTED_ISSUER = "https://auth.example.com";
    private static final String EXPECTED_AUDIENCE = "exeris-kernel";
    private static final String JWT_TYPE = "JWT";

    private final IdentityProviderRegistry registry;
    private final IdentityProvider identityProvider;
    private final Runnable closeAction;

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
        this(identityProvider, () -> { });
    }

    private CommunitySecurityProvider(IdentityProvider identityProvider, Runnable closeAction) {
        this.identityProvider = identityProvider;
        this.registry = IdentityProviderRegistry.of(List.of(identityProvider));
        this.closeAction = closeAction;
    }

    /**
     * Creates a provider that verifies tokens against the key set served at {@code jwksUri},
     * fetched on first use and rotated under {@code policy}
     * ({@link CommunityOidcIdentityProvider#overJwksEndpoint}).
     *
     * <p>The provider owns the client engine the key set is fetched with, and {@link #close()}
     * releases it. An {@code https} URI is fetched only over TLS that verifies the server, so it
     * needs a crypto provider bound when this method runs; an {@code http} URI is plaintext by the
     * caller's request.
     *
     * @param jwksUri the absolute {@code http} or {@code https} URI the JWKS document is served from
     * @param policy the overlap window and stale-fetch budget governing key rotation
     * @param clock the clock driving both rotation timing and token-expiry checks
     * @param expectedIssuer the {@code iss} claim value a token must present to be accepted
     * @param expectedAudience the {@code aud} claim value a token must present to be accepted
     * @return a provider that resolves verification keys through the rotating key set
     * @throws IllegalArgumentException if {@code jwksUri} is not an absolute {@code http} or
     *         {@code https} URI with a host, or carries user information or a fragment
     * @throws IllegalStateException if more than one
     *         {@link eu.exeris.kernel.spi.security.identity.ClaimsMapper} is registered on the
     *         classpath
     * @throws eu.exeris.kernel.spi.exceptions.transport.TransportException ({@code EX-NET-4004}) if
     *         {@code jwksUri} is {@code https} and no engine that verifies its server can be built
     * @since 0.13
     */
    public static CommunitySecurityProvider overJwksEndpoint(URI jwksUri, KeyRotationPolicy policy,
                                                             Clock clock, String expectedIssuer,
                                                             String expectedAudience) {
        // Resolved before the engine exists, so a refusal here leaves nothing to close.
        ClaimsMapper claimsMapper = CommunityClaimsMapperResolver.resolve();
        CommunityOidcIdentityProvider oidc = CommunityOidcIdentityProvider.overJwksEndpoint(
                jwksUri, Map.of(), policy, clock, expectedIssuer, expectedAudience)
                .withClaimsMapper(claimsMapper);
        return new CommunitySecurityProvider(oidc, oidc::close);
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

    /**
     * Releases the client engine the key set is fetched with, when this provider was built by
     * {@link #overJwksEndpoint}; does nothing for a provider over a fixed key map. Idempotent. A
     * token that needs a key refresh after close is denied ({@code EX-SEC-2002}).
     *
     * @since 0.13
     */
    @Override
    public void close() {
        closeAction.run();
    }
}
