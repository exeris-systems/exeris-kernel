/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.security;

/**
 * Internal seam that resolves a verification key for a JWT {@code kid} header.
 *
 * <p>Contract:
 * <ul>
 *   <li>returns the {@link JwsVerificationKey} when the {@code kid} is currently acceptable; the key
 *       carries the one algorithm it is pinned to;</li>
 *   <li>returns {@code null} for a genuinely-unknown {@code kid} (the validator keeps
 *       throwing today's {@code unknown-kid} deny);</li>
 *   <li>throws {@link eu.exeris.kernel.spi.exceptions.security.SecurityAuthenticationException}
 *       ({@code EX-SEC-2002}) for a terminal rotation / staleness deny (fail-closed, ADR-012).</li>
 * </ul>
 *
 * @since 0.9
 */
@FunctionalInterface
/* default */ interface JwksKeyResolver {

    /**
     * Resolves the verification key for the given {@code kid}.
     *
     * @param kid the JWT key-id header value (never {@code null} or blank)
     * @return the matching verification key with its pinned algorithm, or {@code null} when the
     *         {@code kid} is unknown
     * @throws eu.exeris.kernel.spi.exceptions.security.SecurityAuthenticationException
     *         ({@code EX-SEC-2002}) on terminal rotation / staleness deny
     */
    JwsVerificationKey resolve(String kid);
}
