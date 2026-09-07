/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.spi.security.identity;

import eu.exeris.kernel.spi.security.PrincipalContext;

/**
 * SPI: Maps already-verified claims onto a {@link PrincipalContext}.
 *
 * <p>This is the <b>only</b> application-customisable mapping point in the identity pipeline — it
 * is where a deployment decides how {@code sub} becomes a {@code principalId}, which claims carry
 * roles vs scopes, and how a tenant identifier is derived. It maps identity <b>only</b>.
 *
 * <h2>Why identity-only</h2>
 * <p>A {@code ClaimsMapper} deliberately does <em>not</em> produce a
 * {@link eu.exeris.kernel.spi.security.StorageContext}. Tenant-isolation routing is a fail-closed,
 * kernel-owned concern (ADR-012): it is derived uniformly from the same {@link VerifiedClaims} by
 * {@link IdentityStorageMapping} and must not be overridable per application. Splitting the two
 * keeps the customisable surface (identity shape) separate from the non-negotiable surface
 * (isolation deny semantics).
 *
 * @implSpec Implementations must return a deeply-immutable {@link PrincipalContext} that is never
 *           {@code null}. When a required identity claim cannot be mapped (for example a non-UUID
 *           subject), an implementation must raise
 *           {@link eu.exeris.kernel.spi.exceptions.security.SecurityAuthenticationException}
 *           ({@code EX-SEC-2002}) rather than return a degraded principal (fail-closed).
 * @implNote The Community implementation ({@code CommunityClaimsMapper}) is pure and side-effect-free
 *           — O(1) over the supplied claims, with no I/O and no database lookups. That is a property
 *           of the Community binding, not a requirement this interface imposes on every mapper: this
 *           is the one application-customisable seam in the identity pipeline, and a deployment may
 *           legitimately enrich identity from a user-profile store here.
 * @since 0.10
 * @see VerifiedClaims
 * @see IdentityStorageMapping
 */
@FunctionalInterface
public interface ClaimsMapper {

    /**
     * Maps one token's verified claims onto the principal identity this deployment recognises it as.
     *
     * @param claims the verified claims; never {@code null}
     * @return the mapped principal; never {@code null}
     * @throws eu.exeris.kernel.spi.exceptions.security.SecurityAuthenticationException
     *         {@code EX-SEC-2002} — if a required identity claim is missing or unmappable
     */
    PrincipalContext map(VerifiedClaims claims);
}
