/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.community.security;

import com.nimbusds.jwt.SignedJWT;
import eu.exeris.kernel.core.http.client.KernelWebClient;
import eu.exeris.kernel.spi.exceptions.security.SecurityAuthenticationException;
import eu.exeris.kernel.spi.memory.LoanedBuffer;
import eu.exeris.kernel.spi.security.AuthenticationResult;
import eu.exeris.kernel.spi.security.PrincipalContext;
import eu.exeris.kernel.spi.security.StorageContext;
import eu.exeris.kernel.spi.security.identity.ClaimsMapper;
import eu.exeris.kernel.spi.security.identity.IdentityProvider;
import eu.exeris.kernel.spi.security.identity.IdentityStorageMapping;
import eu.exeris.kernel.spi.security.identity.KeyRotationPolicy;
import eu.exeris.kernel.spi.security.identity.TokenValidator;
import eu.exeris.kernel.spi.security.identity.VerifiedClaims;

import java.security.interfaces.RSAPublicKey;
import java.time.Clock;
import java.util.Map;
import java.util.Objects;

/**
 * Community OIDC/JWKS {@link IdentityProvider} (ADR-040): composes the {@link CommunityOidcTokenValidator}
 * cryptographic pipeline, the default {@link CommunityClaimsMapper} identity mapping, and the
 * kernel-owned fail-closed {@link IdentityStorageMapping} into a single
 * {@link AuthenticationResult}.
 *
 * <p>The static {@code kid → RSAPublicKey} map path is byte-for-byte unchanged from the former
 * {@code CommunityJwksValidator}; the rotating-key-set path composes the v0.9
 * {@link CommunityRotatingKeySet} seam through the {@link JwksKeyResolver} constructor.
 *
 * @since 0.10.0
 */
public final class CommunityOidcIdentityProvider implements IdentityProvider {

    private static final String PROVIDER_ID = "oidc-community";
    private static final String PROVIDER_NAME = "ExerisCommunity/OIDC-JWKS";
    private static final String JWT_TYPE = "JWT";

    private final TokenValidator tokenValidator;
    private final ClaimsMapper claimsMapper;
    private final String expectedIssuer;

    public CommunityOidcIdentityProvider(Map<String, RSAPublicKey> keysByKid,
                                         String expectedIssuer,
                                         String expectedAudience) {
        this(new CommunityOidcTokenValidator(keysByKid, expectedIssuer, expectedAudience), expectedIssuer);
    }

    /* default */ CommunityOidcIdentityProvider(JwksKeyResolver keyResolver,
                                                String expectedIssuer,
                                                String expectedAudience) {
        this(new CommunityOidcTokenValidator(keyResolver, expectedIssuer, expectedAudience), expectedIssuer);
    }

    private CommunityOidcIdentityProvider(TokenValidator tokenValidator, String expectedIssuer) {
        this.tokenValidator = Objects.requireNonNull(tokenValidator, "tokenValidator must not be null");
        this.claimsMapper = new CommunityClaimsMapper();
        this.expectedIssuer = Objects.requireNonNull(expectedIssuer, "expectedIssuer must not be null");
    }

    /**
     * Assembles an OIDC provider that refreshes its verification keys from a live JWKS endpoint:
     * a {@link CommunityJwksHttpKeySetSource} (one GET via {@code jwksClient}) feeds the v0.9
     * {@link CommunityRotatingKeySet}, applying {@code policy} on the supplied {@code clock}.
     * {@code initialKeys} seeds the first generation (may be empty to force a fetch on first use).
     *
     * <p>{@code jwksClient} must decode a {@code String.class} response as the raw response text
     * (see {@link CommunityJwksHttpKeySetSource#overWebClient}) — wire it with a
     * {@link eu.exeris.kernel.community.http.CommunityTextResponseBodyDecoder}.
     */
    public static CommunityOidcIdentityProvider overJwksEndpoint(
            KernelWebClient jwksClient, String jwksPath,
            Map<String, RSAPublicKey> initialKeys, KeyRotationPolicy policy, Clock clock,
            String expectedIssuer, String expectedAudience) {
        KeySetSource source = CommunityJwksHttpKeySetSource.overWebClient(jwksClient, jwksPath);
        JwksKeyResolver resolver = new CommunityRotatingKeySet(initialKeys, source, policy, clock);
        // Same clock drives both rotation timing and token-expiry — no wall-clock skew between them.
        TokenValidator validator = new CommunityOidcTokenValidator(resolver, expectedIssuer, expectedAudience, clock);
        return new CommunityOidcIdentityProvider(validator, expectedIssuer);
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

    /**
     * Routing peek: this provider attempts a token whose unverified {@code iss} matches the
     * configured issuer. Grants nothing — every trust decision flows through {@link #authenticate}.
     * Never throws; an unrecognised or unparseable token simply returns {@code false}.
     */
    @Override
    @SuppressWarnings("PMD.AvoidCatchingGenericException") // routing peek MUST NOT throw
    public boolean canAttempt(LoanedBuffer rawToken) {
        try {
            String compactJwt = CommunityOidcTokenValidator.readCompactJwt(rawToken);
            String issuer = SignedJWT.parse(compactJwt).getJWTClaimsSet().getIssuer();
            return expectedIssuer.equals(issuer);
        } catch (Exception _) {
            return false;
        }
    }

    @Override
    public AuthenticationResult authenticate(LoanedBuffer rawToken) {
        long startNanos = System.nanoTime();
        try {
            VerifiedClaims claims = tokenValidator.validate(rawToken);
            PrincipalContext principal = claimsMapper.map(claims);
            StorageContext storage = IdentityStorageMapping.fromClaims(claims, principal.principalId(), JWT_TYPE);
            // Single-phase JFR commit AFTER (possibly blocking) validation — never straddle a VT.
            CommunityIdentityJfrEvents.emitValidation(PROVIDER_ID, claims.issuer(), startNanos);
            return new AuthenticationResult(principal, storage);
        } catch (SecurityAuthenticationException ex) {
            Object[] args = ex.rawArgs();
            String reason = args.length > 1 ? String.valueOf(args[1]) : "validation-failed";
            CommunityIdentityJfrEvents.emitRejection(PROVIDER_ID, reason);
            throw ex;
        }
    }
}
