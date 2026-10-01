/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.spi.security.identity;

import eu.exeris.kernel.spi.memory.LoanedBuffer;

/**
 * SPI: The cryptographic-verification half of an {@link IdentityProvider}.
 *
 * <p>A {@code TokenValidator} owns the ordered, fail-closed verification pipeline for one token
 * format — for an OIDC/JWKS driver that is {@code kid → key → algorithm → signature → issuer →
 * audience → time}. It returns the verified claims as a format-blind {@link VerifiedClaims}, or
 * throws to deny.
 *
 * <p><b>Ownership:</b> the caller owns the {@link LoanedBuffer} passed to {@link #validate}; the
 * validator must not close it or retain it beyond that call.
 *
 * @implSpec Any uncertainty — a malformed token, an unknown or absent key, an algorithm mismatch,
 *           a bad signature, a wrong issuer or audience, expiry, or an unreachable key source —
 *           must raise
 *           {@link eu.exeris.kernel.spi.exceptions.security.SecurityAuthenticationException}
 *           ({@code EX-SEC-2002}); there is no fail-open and no {@code null} return. The
 *           algorithm must be pinned before signature verification and never inferred from the
 *           token header alone (algorithm-confusion defence). Deny reasons carried in the
 *           exception must be secret-safe: never the raw token, key material, or a sensitive
 *           claim value. The validator must not close or retain {@code rawToken} beyond the call.
 * @since 0.10
 * @see IdentityProvider
 * @see VerifiedClaims
 */
@FunctionalInterface
public interface TokenValidator {

    /**
     * Verifies the raw token and returns its verified claims.
     *
     * @param rawToken the raw credential in a caller-owned loaned buffer; never {@code null}
     * @return the verified claims; never {@code null}
     * @throws eu.exeris.kernel.spi.exceptions.security.SecurityAuthenticationException
     *         {@code EX-SEC-2002} — on any validation failure (terminal deny)
     */
    VerifiedClaims validate(LoanedBuffer rawToken);
}
