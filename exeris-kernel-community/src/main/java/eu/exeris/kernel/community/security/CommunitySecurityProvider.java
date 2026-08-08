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
 * <p>This refactor moves the JWT pipeline into {@link CommunityOidcIdentityProvider} /
 * {@link CommunityOidcTokenValidator}; the static {@code kid → key} path is byte-for-byte unchanged.
 *
 * <p>ServiceLoader-based discovery of {@link IdentityProvider} bindings and config-driven
 * construction (issuer / audience / JWKS endpoint) land with the config wiring step; today the
 * registry is built from the directly-constructed Community OIDC provider.
 *
 * @since 0.5.0
 */
public final class CommunitySecurityProvider implements SecurityProvider {

    private static final String PROVIDER_ID = "jwt-community";
    private static final String PROVIDER_NAME = "ExerisCommunity/JWT";
    private static final String EXPECTED_ISSUER = "https://auth.example.com";
    private static final String EXPECTED_AUDIENCE = "exeris-kernel";
    private static final String JWT_TYPE = "JWT";

    private final IdentityProviderRegistry registry;
    private final IdentityProvider identityProvider;

    /** Public no-arg constructor required by {@link java.util.ServiceLoader}. */
    public CommunitySecurityProvider() {
        this(Map.of(), EXPECTED_ISSUER, EXPECTED_AUDIENCE);
    }

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
     * {@link CommunityClaimsMapperResolver}. Without it the reachability fix has no failing test —
     * reverting the wiring compiles and every other assertion still passes, which is exactly what a
     * mutation run showed before this existed.
     */
    /* default */ IdentityProvider identityProvider() {
        return identityProvider;
    }

    @Override
    public String providerId() {
        return PROVIDER_ID;
    }

    @Override
    public String providerName() {
        return PROVIDER_NAME;
    }

    @Override
    public int priority() {
        return 0;
    }

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

    @Override
    public StorageContext systemStorageContext() {
        return ImmutableStorageContext.GLOBAL;
    }
}
