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
import eu.exeris.kernel.spi.security.AuthenticationResult;
import eu.exeris.kernel.spi.security.ImmutablePrincipal;
import eu.exeris.kernel.spi.security.ImmutableStorageContext;
import eu.exeris.kernel.spi.security.KernelIsolationClaims;

import java.security.interfaces.RSAPublicKey;
import java.text.ParseException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Community JWT validator based on an immutable kid->RSA public key map.
 *
 * @since 0.5.0
 */
@SuppressWarnings("PMD.CyclomaticComplexity")
final class CommunityJwksValidator {

    private static final String JWT_TYPE = "JWT";
    private static final String ERR_CLAIMS_MISSING = "claims-missing";

    private final JwksKeyResolver keyResolver;
    private final String expectedIssuer;
    private final String expectedAudience;

    /* default */ CommunityJwksValidator(
            Map<String, RSAPublicKey> keysByKid, String expectedIssuer, String expectedAudience) {
        this(new StaticJwksKeyResolver(Objects.requireNonNull(keysByKid, "keysByKid must not be null")),
                expectedIssuer, expectedAudience);
    }

    /* default */ CommunityJwksValidator(
            JwksKeyResolver keyResolver, String expectedIssuer, String expectedAudience) {
        this.keyResolver = Objects.requireNonNull(keyResolver, "keyResolver must not be null");
        this.expectedIssuer = Objects.requireNonNull(expectedIssuer, "expectedIssuer must not be null");
        this.expectedAudience = Objects.requireNonNull(expectedAudience, "expectedAudience must not be null");
    }

    /* default */ AuthenticationResult authenticate(String compactJwt) {
        SignedJWT signedJwt = parseJwt(compactJwt);

        String kid = signedJwt.getHeader().getKeyID();
        if (kid == null || kid.isBlank()) {
            throw new SecurityAuthenticationException(JWT_TYPE, "missing-kid");
        }

        RSAPublicKey publicKey = keyResolver.resolve(kid);
        if (publicKey == null) {
            throw new SecurityAuthenticationException(JWT_TYPE, "unknown-kid");
        }

        if (!JWSAlgorithm.RS256.equals(signedJwt.getHeader().getAlgorithm())) {
            throw new SecurityAuthenticationException(JWT_TYPE, "signature-invalid");
        }

        verifySignature(signedJwt, publicKey);

        JWTClaimsSet claims = extractClaims(signedJwt);
        validateIssuer(claims);
        validateAudience(claims);
        validateExpiry(claims);

        String subject = claims.getSubject();
        if (subject == null || subject.isBlank()) {
            throw new SecurityAuthenticationException(JWT_TYPE, ERR_CLAIMS_MISSING);
        }

        UUID subjectUuid;
        try {
            subjectUuid = UUID.fromString(subject);
        } catch (IllegalArgumentException _) {
            throw new SecurityAuthenticationException(JWT_TYPE, "invalid-subject");
        }

        ImmutableStorageContext storageCtx =
                resolveStorageContext(claims, subject, subjectUuid);
        return new AuthenticationResult(
                ImmutablePrincipal.ofTenant(subjectUuid, subjectUuid, Set.of(), Set.of("security:read")),
                storageCtx);
    }

    private static ImmutableStorageContext resolveStorageContext(
            JWTClaimsSet claims, String subject, UUID subjectUuid) {
        String strategyStr;
        try {
            strategyStr = claims.getStringClaim(KernelIsolationClaims.ISOLATION_STRATEGY);
        } catch (ParseException _) {
            // Claim present but wrong type — fail-closed
            return ImmutableStorageContext.shared(
                    subjectUuid.getMostSignificantBits(), subjectUuid.getLeastSignificantBits());
        }

        if (strategyStr == null || strategyStr.isBlank()) {
            return ImmutableStorageContext.shared(
                    subjectUuid.getMostSignificantBits(), subjectUuid.getLeastSignificantBits());
        }

        return switch (strategyStr) {
            case "SEPARATED_SCHEMA" -> {
                String schemaName = null;
                try {
                    schemaName = claims.getStringClaim(KernelIsolationClaims.SCHEMA_NAME);
                } catch (ParseException _) {
                    // ignored
                }
                if (schemaName == null || schemaName.isBlank()) {
                    // Missing schema claim — fail-closed
                    yield ImmutableStorageContext.shared(
                            subjectUuid.getMostSignificantBits(), subjectUuid.getLeastSignificantBits());
                }
                yield ImmutableStorageContext.separatedSchema(subject, schemaName);
            }
            case "DEDICATED" -> {
                String dataSourceKey = null;
                try {
                    dataSourceKey = claims.getStringClaim(KernelIsolationClaims.DATASOURCE_KEY);
                } catch (ParseException _) {
                    // ignored
                }
                if (dataSourceKey == null || dataSourceKey.isBlank()) {
                    // Missing datasource key claim — fail-closed
                    yield ImmutableStorageContext.shared(
                            subjectUuid.getMostSignificantBits(), subjectUuid.getLeastSignificantBits());
                }
                yield ImmutableStorageContext.dedicated(subject, dataSourceKey);
            }
            // Unrecognised strategy value — fail-closed
            default -> ImmutableStorageContext.shared(
                    subjectUuid.getMostSignificantBits(), subjectUuid.getLeastSignificantBits());
        };
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
}