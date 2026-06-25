/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
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
 * <h2>Fail-closed contract (ADR-012)</h2>
 * <ul>
 *   <li>Any uncertainty — malformed token, unknown/absent key, algorithm mismatch, bad signature,
 *       wrong issuer/audience, expired, unreachable key source — MUST raise
 *       {@link eu.exeris.kernel.spi.exceptions.security.SecurityAuthenticationException}
 *       ({@code EX-SEC-2002}). There is no fail-open and no {@code null} return.</li>
 *   <li>The algorithm MUST be pinned <b>before</b> signature verification and never inferred from
 *       the token header alone (algorithm-confusion defence).</li>
 *   <li>Deny reasons carried in the exception are secret-safe: never the raw token, key material,
 *       or a sensitive claim value.</li>
 * </ul>
 *
 * <h2>Zero-copy contract</h2>
 * <p>The raw token arrives in a caller-owned {@link LoanedBuffer}. The validator MUST NOT close or
 * retain it beyond the call.
 *
 * @since 0.10.0
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
     *         on any validation failure (terminal deny)
     */
    VerifiedClaims validate(LoanedBuffer rawToken);
}
