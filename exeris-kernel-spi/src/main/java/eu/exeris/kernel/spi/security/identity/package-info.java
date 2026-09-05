/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
/**
 * SPI: Pluggable identity / token-validation seam (ADR-040).
 *
 * <h2>Shape</h2>
 * <p>{@link eu.exeris.kernel.spi.security.identity.IdentityProvider} validates a raw credential
 * carried in a {@link eu.exeris.kernel.spi.memory.LoanedBuffer} into an
 * {@link eu.exeris.kernel.spi.security.AuthenticationResult}.
 * {@link eu.exeris.kernel.spi.security.SecurityProvider} keeps its single entry point but becomes
 * a thin dispatcher: it selects exactly one provider via
 * {@link eu.exeris.kernel.spi.security.identity.IdentityProviderRegistry}, delegates validation,
 * and applies the cross-cutting fail-closed concerns uniformly (ADR-012).
 *
 * <p>{@link eu.exeris.kernel.spi.security.identity.TokenValidator} (cryptographic verification)
 * and {@link eu.exeris.kernel.spi.security.identity.ClaimsMapper} (identity mapping) are the
 * per-driver composition seams; {@link eu.exeris.kernel.spi.security.identity.VerifiedClaims} is
 * the format-blind carrier they exchange. The single fail-closed isolation-claim →
 * {@link eu.exeris.kernel.spi.security.StorageContext} mapping lives in
 * {@link eu.exeris.kernel.spi.security.identity.IdentityStorageMapping} so no driver re-implements
 * it.
 *
 * <h2>The Wall (ADR-006)</h2>
 * <p>No JWT / JWKS / OIDC / PASETO / RSA / HTTP vocabulary appears in this package. Those concerns
 * live entirely in the Community / Enterprise drivers. This package is a pure contract.
 *
 * @since 0.10
 */
package eu.exeris.kernel.spi.security.identity;
