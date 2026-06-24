/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.community.security;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import eu.exeris.kernel.spi.exceptions.security.SecurityAuthenticationException;
import eu.exeris.kernel.spi.memory.LoanedBuffer;
import eu.exeris.kernel.spi.security.KernelIsolationClaims;
import eu.exeris.kernel.spi.security.identity.TokenValidator;
import eu.exeris.kernel.spi.security.identity.VerifiedClaims;

import java.lang.foreign.ValueLayout;
import java.nio.charset.StandardCharsets;
import java.security.interfaces.RSAPublicKey;
import java.text.ParseException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Community OIDC/JWKS {@link TokenValidator}: the ordered, fail-closed RS256 verification pipeline
 * ({@code kid → key → algorithm → signature → issuer → audience → time}) over a JWT, producing
 * format-blind {@link VerifiedClaims}.
 *
 * <p>Extracted from the former {@code CommunityJwksValidator}: the cryptographic pipeline is
 * unchanged (static-map path byte-for-byte), but tenant-isolation → {@code StorageContext} mapping
 * and principal assembly now live above this seam (in
 * {@link eu.exeris.kernel.spi.security.identity.IdentityStorageMapping} and
 * {@link CommunityClaimsMapper}).
 *
 * @since 0.10.0
 */
@SuppressWarnings("PMD.CyclomaticComplexity")
final class CommunityOidcTokenValidator implements TokenValidator {

    private static final String JWT_TYPE = "JWT";
    private static final String ERR_CLAIMS_MISSING = "claims-missing";

    private final JwksKeyResolver keyResolver;
    private final String expectedIssuer;
    private final String expectedAudience;

    /* default */ CommunityOidcTokenValidator(
            Map<String, RSAPublicKey> keysByKid, String expectedIssuer, String expectedAudience) {
        this(new StaticJwksKeyResolver(Objects.requireNonNull(keysByKid, "keysByKid must not be null")),
                expectedIssuer, expectedAudience);
    }

    /* default */ CommunityOidcTokenValidator(
            JwksKeyResolver keyResolver, String expectedIssuer, String expectedAudience) {
        this.keyResolver = Objects.requireNonNull(keyResolver, "keyResolver must not be null");
        this.expectedIssuer = Objects.requireNonNull(expectedIssuer, "expectedIssuer must not be null");
        this.expectedAudience = Objects.requireNonNull(expectedAudience, "expectedAudience must not be null");
    }

    /**
     * Reads the compact JWT string out of a caller-owned {@link LoanedBuffer}, applying the same
     * size guards as the legacy provider edge. Shared by {@link #validate(LoanedBuffer)} and the
     * provider's {@code canAttempt} routing peek.
     */
    /* default */ static String readCompactJwt(LoanedBuffer rawToken) {
        if (rawToken == null) {
            throw new SecurityAuthenticationException(JWT_TYPE, "missing-token");
        }
        long tokenSize = rawToken.size();
        if (tokenSize < 0) {
            throw new SecurityAuthenticationException(JWT_TYPE, "indeterminate");
        }
        if (tokenSize == 0) {
            throw new SecurityAuthenticationException(JWT_TYPE, "empty-token");
        }
        byte[] tokenBytes = rawToken.segment().asSlice(0L, tokenSize).toArray(ValueLayout.JAVA_BYTE);
        return new String(tokenBytes, StandardCharsets.UTF_8);
    }

    @Override
    public VerifiedClaims validate(LoanedBuffer rawToken) {
        SignedJWT signedJwt = parseJwt(readCompactJwt(rawToken));

        String kid = signedJwt.getHeader().getKeyID();
        if (kid == null || kid.isBlank()) {
            throw new SecurityAuthenticationException(JWT_TYPE, "missing-kid");
        }

        RSAPublicKey publicKey = keyResolver.resolve(kid);
        if (publicKey == null) {
            throw new SecurityAuthenticationException(JWT_TYPE, "unknown-kid");
        }

        // Algorithm pinned BEFORE signature verification (algorithm-confusion defence).
        if (!JWSAlgorithm.RS256.equals(signedJwt.getHeader().getAlgorithm())) {
            throw new SecurityAuthenticationException(JWT_TYPE, "signature-invalid");
        }

        verifySignature(signedJwt, publicKey);

        JWTClaimsSet claims = extractClaims(signedJwt);
        validateIssuer(claims);
        validateAudience(claims);
        validateExpiry(claims);
        validateIsolationStrategyWellTyped(claims);

        return new CommunityVerifiedClaims(claims);
    }

    private static SignedJWT parseJwt(String compactJwt) {
        try {
            return SignedJWT.parse(compactJwt);
        } catch (ParseException _) {
            throw new SecurityAuthenticationException(JWT_TYPE, "malformed");
        }
    }

    private static void verifySignature(SignedJWT signedJwt, RSAPublicKey publicKey) {
        try {
            if (!signedJwt.verify(new RSASSAVerifier(publicKey))) {
                throw new SecurityAuthenticationException(JWT_TYPE, "signature-invalid");
            }
        } catch (JOSEException _) {
            throw new SecurityAuthenticationException(JWT_TYPE, "signature-invalid");
        }
    }

    private static JWTClaimsSet extractClaims(SignedJWT signedJwt) {
        JWTClaimsSet claims;
        try {
            claims = signedJwt.getJWTClaimsSet();
        } catch (ParseException _) {
            throw new SecurityAuthenticationException(JWT_TYPE, ERR_CLAIMS_MISSING);
        }
        if (claims == null) {
            throw new SecurityAuthenticationException(JWT_TYPE, ERR_CLAIMS_MISSING);
        }
        return claims;
    }

    private void validateIssuer(JWTClaimsSet claims) {
        if (!expectedIssuer.equals(claims.getIssuer())) {
            throw new SecurityAuthenticationException(JWT_TYPE, "issuer-mismatch");
        }
    }

    private void validateAudience(JWTClaimsSet claims) {
        List<String> audience = claims.getAudience();
        if (audience == null || audience.isEmpty() || !audience.contains(expectedAudience)) {
            throw new SecurityAuthenticationException(JWT_TYPE, "audience-mismatch");
        }
    }

    private static void validateExpiry(JWTClaimsSet claims) {
        if (claims.getExpirationTime() == null) {
            throw new SecurityAuthenticationException(JWT_TYPE, ERR_CLAIMS_MISSING);
        }
        Instant expiry = claims.getExpirationTime().toInstant();
        if (expiry.isBefore(Instant.now())) {
            throw new SecurityAuthenticationException(JWT_TYPE, "expired");
        }
    }

    /**
     * A declared isolation-strategy claim that is present but not a string is malformed security
     * input — terminal deny, never a silent downgrade to SHARED (S-P0-07; ADR-012 §4a amended).
     * Verifying it here keeps {@link VerifiedClaims#claim(String)} free of deny logic; an absent or
     * well-typed strategy is left to {@link eu.exeris.kernel.spi.security.identity.IdentityStorageMapping}.
     */
    private static void validateIsolationStrategyWellTyped(JWTClaimsSet claims) {
        try {
            claims.getStringClaim(KernelIsolationClaims.ISOLATION_STRATEGY);
        } catch (ParseException _) {
            throw new SecurityAuthenticationException(JWT_TYPE, "isolation-malformed");
        }
    }
}
