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
 *   <li>A declared {@link KernelIsolationClaims#SHARED_SCOPE_KEY} is carried onto the resolved context
 *       only where the deployment asserts {@link #SHARED_SCOPE_ENFORCED_KEY}; otherwise it is a terminal
 *       deny ({@code shared-scope-unsupported}). Neither narrowing it away nor honouring it unenforced
 *       is permitted (ADR-012 §4b.5).</li>
 * </ul>
 *
 * @since 0.10.0
 * @see KernelIsolationClaims
 * @see ClaimsMapper
 */
public final class IdentityStorageMapping {

    /**
     * Configuration key by which a deployment asserts that its storage schema implements the
     * shared-scope policy contract (ADR-012 §4b.4): a read predicate that widens on the published
     * shared scope, and a write predicate still pinned to the owner.
     *
     * <p><b>Why this is an assertion and not a probe.</b> The kernel ships no RLS policy and cannot
     * introspect the one a deployment wrote — the policy lives in the application's DDL. Nothing inside
     * the kernel, the persistence engine included, is in a position to know whether a shared scope will
     * actually be honoured; only whoever owns the schema knows that. Asking the engine would turn an
     * operator's claim into an apparent kernel guarantee, which is worse than asking plainly.
     *
     * <p>Absent or {@code false} means unenforceable, and a declared shared scope is therefore denied.
     * That default is what keeps the tier fail-closed for every deployment that has not opted in.
     *
     * <p><b>Names the key; nothing reads it yet.</b> No kernel component resolves this property from a
     * configuration source today — the assertion reaches
     * {@link #fromClaims(VerifiedClaims, UUID, String, boolean)} through explicit provider construction,
     * so setting the property has no effect before the config-wiring step that still owns issuer,
     * audience, and JWKS endpoint. The constant exists now to fix the name that step will use, so
     * operator-facing documentation and the eventual wiring cannot drift apart.
     *
     * @since 0.11.0
     */
    public static final String SHARED_SCOPE_ENFORCED_KEY = "exeris.security.shared-scope.enforced";

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
        return fromClaims(claims, subjectId, tokenType, false);
    }

    /**
     * Derives the fail-closed {@link StorageContext} for a verified token, honouring a declared shared
     * scope only where the deployment has asserted it can enforce one.
     *
     * @param claims               the verified claims; never {@code null}
     * @param subjectId            the principal's resolved UUID; never {@code null}
     * @param tokenType            the token-type label for any deny exception's secret-safe
     *                             {@code rawArgs}; never {@code null}
     * @param sharedScopeEnforced  whether this deployment asserts its schema implements the shared-scope
     *                             policy contract — see {@link #SHARED_SCOPE_ENFORCED_KEY}. When
     *                             {@code false}, a declared shared scope is a terminal deny rather than
     *                             a silent narrowing (ADR-012 §4b.5)
     * @return the resolved storage context; never {@code null}
     * @throws SecurityAuthenticationException on an incomplete or unrecognised isolation declaration, or
     *         on a shared scope this deployment cannot enforce
     * @since 0.11.0
     */
    public static StorageContext fromClaims(VerifiedClaims claims, UUID subjectId, String tokenType,
                                            boolean sharedScopeEnforced) {
        Objects.requireNonNull(claims, "claims must not be null");
        Objects.requireNonNull(subjectId, "subjectId must not be null");
        Objects.requireNonNull(tokenType, "tokenType must not be null");

        String sharedScope = resolveSharedScope(claims, tokenType, sharedScopeEnforced);

        String strategy = claims.claim(KernelIsolationClaims.ISOLATION_STRATEGY).orElse(null);
        if (strategy == null || strategy.isBlank()) {
            return withScope(sharedFor(subjectId), sharedScope);
        }

        // Strong strategies key on the verified subject string (the tenant identifier), matching
        // the value the SHARED path derives from; subjectId provides the SHARED bit-packing. The
        // VerifiedClaims contract requires a non-blank subject, but a broken driver must still
        // fail closed here rather than NPE inside ImmutableStorageContext.
        String subject = requireSubject(claims, tokenType);
        ImmutableStorageContext resolved = switch (strategy) {
            case "SHARED" -> sharedFor(subjectId);
            case "SEPARATED_SCHEMA" -> ImmutableStorageContext.separatedSchema(
                    subject, require(claims, KernelIsolationClaims.SCHEMA_NAME, tokenType));
            case "DEDICATED" -> ImmutableStorageContext.dedicated(
                    subject, require(claims, KernelIsolationClaims.DATASOURCE_KEY, tokenType));
            default -> throw new SecurityAuthenticationException(tokenType, ERR_UNKNOWN);
        };
        return withScope(resolved, sharedScope);
    }

    /** Attaches {@code sharedScope} when one survived {@link #resolveSharedScope}; identity otherwise. */
    private static StorageContext withScope(ImmutableStorageContext context, String sharedScope) {
        return sharedScope == null ? context : context.withSharedScope(sharedScope);
    }

    /**
     * Resolves a declared shared scope against what this deployment says it can enforce.
     *
     * <p>Three outcomes, and per ADR-012 §4b.5 there is deliberately no fourth:
     * <ul>
     *   <li>no scope declared → {@code null}, the tenant-private default;</li>
     *   <li>declared and {@code sharedScopeEnforced} → carried onto the resolved context;</li>
     *   <li>declared and <b>not</b> enforced → terminal deny. Not a silent narrowing to tenant-private,
     *       which would give the caller less than it asked for without saying so, and not a widening,
     *       which would hand back a context claiming visibility nothing enforces.</li>
     * </ul>
     *
     * <p>The deny is conditional on the deployment rather than absolute, but it never disappears: it
     * remains wherever enforcement is absent, so no window exists in which a declared scope resolves to
     * anything but deny or correct enforcement.
     *
     * <p><b>Wrong-typed claim — now a live driver obligation.</b>
     * {@link VerifiedClaims#claim(String)} reports a present-but-not-single-string claim as absent, so a
     * wrong-typed shared-scope claim arrives here as "no shared scope declared" and yields the
     * tenant-private default. While every declared scope was denied outright that was merely a
     * narrowing, and therefore tolerable. It is not tolerable any more: in a deployment that asserts
     * {@link #SHARED_SCOPE_ENFORCED_KEY}, a caller whose scope claim is malformed silently loses the
     * shared visibility it asked for instead of being told. Type-checking this claim during token
     * validation is consequently a {@code TokenValidator} obligation on the same footing as
     * {@link KernelIsolationClaims#ISOLATION_STRATEGY} (ADR-012 §4a enforcement layers) — the mapping
     * structurally cannot make it.
     */
    private static String resolveSharedScope(VerifiedClaims claims, String tokenType,
                                             boolean sharedScopeEnforced) {
        String sharedScope = claims.claim(KernelIsolationClaims.SHARED_SCOPE_KEY).orElse(null);
        if (sharedScope == null || sharedScope.isBlank()) {
            return null;
        }
        if (!sharedScopeEnforced) {
            throw new SecurityAuthenticationException(tokenType, ERR_SHARED_SCOPE_UNSUPPORTED);
        }
        return sharedScope;
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
