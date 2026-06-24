/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.spi.security.identity;

import eu.exeris.kernel.spi.exceptions.security.SecurityAuthenticationException;
import eu.exeris.kernel.spi.security.ImmutableStorageContext;
import eu.exeris.kernel.spi.security.KernelIsolationClaims;
import eu.exeris.kernel.spi.security.StorageContext;

import java.util.Objects;
import java.util.UUID;

/**
 * SPI: The single, kernel-owned, fail-closed mapping from a token's
 * {@link KernelIsolationClaims} to a {@link StorageContext} (ADR-040 §2.4, ADR-012).
 *
 * <p>This is intentionally <b>not</b> overridable by an application {@link ClaimsMapper}: tenant
 * isolation is a deny-on-uncertainty concern that must live in exactly one place so every
 * {@link IdentityProvider} routes identically. A {@code ClaimsMapper} shapes identity; this maps
 * isolation.
 *
 * <h2>Fail-closed rules (S-P0-07, ADR-012 §4a/§5)</h2>
 * <ul>
 *   <li>No strategy claim declared → {@code SHARED} keyed on the subject (the only permissive
 *       fall-through — no isolation intent was expressed).</li>
 *   <li>{@code SHARED} → shared context.</li>
 *   <li>{@code SEPARATED_SCHEMA} → requires {@link KernelIsolationClaims#SCHEMA_NAME}; a missing or
 *       blank sub-claim is a terminal deny ({@code isolation-incomplete}), never a downgrade to
 *       {@code SHARED}.</li>
 *   <li>{@code DEDICATED} → requires {@link KernelIsolationClaims#DATASOURCE_KEY}; same deny rule.</li>
 *   <li>A declared-but-unrecognised strategy is a terminal deny ({@code isolation-unknown-strategy})
 *       — producing {@code SHARED} (the weakest tier) here would be fail-open.</li>
 * </ul>
 *
 * @since 0.10.0
 * @see KernelIsolationClaims
 * @see ClaimsMapper
 */
public final class IdentityStorageMapping {

    private static final String ERR_INCOMPLETE = "isolation-incomplete";
    private static final String ERR_UNKNOWN = "isolation-unknown-strategy";

    private IdentityStorageMapping() {
        // Utility class — not instantiable.
    }

    /**
     * Derives the fail-closed {@link StorageContext} for a verified token.
     *
     * @param claims    the verified claims; never {@code null}
     * @param subjectId the principal's resolved UUID (used for the {@code SHARED} isolation key and
     *                  as the tenant identifier for strong strategies); never {@code null}
     * @param tokenType the token-type label for any deny exception's secret-safe {@code rawArgs}
     *                  (e.g. {@code "JWT"}); never {@code null}
     * @return the resolved storage context; never {@code null}
     * @throws SecurityAuthenticationException on an incomplete or unrecognised isolation declaration
     */
    public static StorageContext fromClaims(VerifiedClaims claims, UUID subjectId, String tokenType) {
        Objects.requireNonNull(claims, "claims must not be null");
        Objects.requireNonNull(subjectId, "subjectId must not be null");
        Objects.requireNonNull(tokenType, "tokenType must not be null");

        String strategy = claims.claim(KernelIsolationClaims.ISOLATION_STRATEGY).orElse(null);
        if (strategy == null || strategy.isBlank()) {
            return sharedFor(subjectId);
        }

        // Strong strategies key on the verified subject string (the tenant identifier), matching
        // the value the SHARED path derives from; subjectId provides the SHARED bit-packing.
        String subject = claims.subject();
        return switch (strategy) {
            case "SHARED" -> sharedFor(subjectId);
            case "SEPARATED_SCHEMA" -> ImmutableStorageContext.separatedSchema(
                    subject, require(claims, KernelIsolationClaims.SCHEMA_NAME, tokenType));
            case "DEDICATED" -> ImmutableStorageContext.dedicated(
                    subject, require(claims, KernelIsolationClaims.DATASOURCE_KEY, tokenType));
            default -> throw new SecurityAuthenticationException(tokenType, ERR_UNKNOWN);
        };
    }

    private static ImmutableStorageContext sharedFor(UUID subjectId) {
        return ImmutableStorageContext.shared(
                subjectId.getMostSignificantBits(), subjectId.getLeastSignificantBits());
    }

    private static String require(VerifiedClaims claims, String claimName, String tokenType) {
        String value = claims.claim(claimName).orElse(null);
        if (value == null || value.isBlank()) {
            throw new SecurityAuthenticationException(tokenType, ERR_INCOMPLETE);
        }
        return value;
    }
}
