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
 *   <li>A declared {@link KernelIsolationClaims#SHARED_SCOPE_KEY} is a terminal deny
 *       ({@code shared-scope-unsupported}) while no binding can enforce shared visibility — neither
 *       narrowing it away nor honouring it unenforced is permitted (ADR-012 §4b.5).</li>
 * </ul>
 *
 * @since 0.10.0
 * @see KernelIsolationClaims
 * @see ClaimsMapper
 */
public final class IdentityStorageMapping {

    private static final String ERR_INCOMPLETE = "isolation-incomplete";
    private static final String ERR_UNKNOWN = "isolation-unknown-strategy";
    private static final String ERR_SHARED_SCOPE_UNSUPPORTED = "shared-scope-unsupported";

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

        rejectUnenforceableSharedScope(claims, tokenType);

        String strategy = claims.claim(KernelIsolationClaims.ISOLATION_STRATEGY).orElse(null);
        if (strategy == null || strategy.isBlank()) {
            return sharedFor(subjectId);
        }

        // Strong strategies key on the verified subject string (the tenant identifier), matching
        // the value the SHARED path derives from; subjectId provides the SHARED bit-packing. The
        // VerifiedClaims contract requires a non-blank subject, but a broken driver must still
        // fail closed here rather than NPE inside ImmutableStorageContext.
        String subject = requireSubject(claims, tokenType);
        return switch (strategy) {
            case "SHARED" -> sharedFor(subjectId);
            case "SEPARATED_SCHEMA" -> ImmutableStorageContext.separatedSchema(
                    subject, require(claims, KernelIsolationClaims.SCHEMA_NAME, tokenType));
            case "DEDICATED" -> ImmutableStorageContext.dedicated(
                    subject, require(claims, KernelIsolationClaims.DATASOURCE_KEY, tokenType));
            default -> throw new SecurityAuthenticationException(tokenType, ERR_UNKNOWN);
        };
    }

    /**
     * Fail-closed handling of a declared shared scope while no binding can enforce one.
     *
     * <p>Per ADR-012 §4b.5 a declared-but-unenforceable shared scope is a terminal deny — never a silent
     * narrowing to tenant-private, and never a widening. No persistence binding implements the
     * read-widen / owner-scoped-write mode yet, so the claim is currently unconditionally unenforceable.
     * When a binding gains that mode, this check becomes conditional on the running deployment rather
     * than disappearing: the deny must remain wherever enforcement is absent, so there is never a window
     * in which the claim resolves to anything but deny or correct enforcement.
     *
     * <p><b>Wrong-typed claim caveat.</b> {@link VerifiedClaims#claim(String)} reports a
     * present-but-not-single-string claim as absent, so a wrong-typed shared-scope claim reaches this
     * check as "no shared scope declared" and yields the tenant-private default. That is a narrowing,
     * not a widening, so it is safe while the enforceable answer is deny anyway. It stops being safe the
     * moment a binding can honour the claim — at that point type-checking this claim during token
     * validation becomes a driver obligation, exactly as it already is for
     * {@link KernelIsolationClaims#ISOLATION_STRATEGY} (ADR-012 §4a enforcement layers).
     */
    private static void rejectUnenforceableSharedScope(VerifiedClaims claims, String tokenType) {
        String sharedScope = claims.claim(KernelIsolationClaims.SHARED_SCOPE_KEY).orElse(null);
        if (sharedScope != null && !sharedScope.isBlank()) {
            throw new SecurityAuthenticationException(tokenType, ERR_SHARED_SCOPE_UNSUPPORTED);
        }
    }

    private static String requireSubject(VerifiedClaims claims, String tokenType) {
        String subject = claims.subject();
        if (subject == null || subject.isBlank()) {
            throw new SecurityAuthenticationException(tokenType, ERR_INCOMPLETE);
        }
        return subject;
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
