/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.security;

import java.security.interfaces.RSAPublicKey;

/**
 * Internal seam that resolves a verification key for a JWT {@code kid} header.
 *
 * <p>Contract:
 * <ul>
 *   <li>returns the {@link RSAPublicKey} when the {@code kid} is currently acceptable;</li>
 *   <li>returns {@code null} for a genuinely-unknown {@code kid} (the validator keeps
 *       throwing today's {@code unknown-kid} deny);</li>
 *   <li>throws {@link eu.exeris.kernel.spi.exceptions.security.SecurityAuthenticationException}
 *       for a terminal rotation / staleness deny (fail-closed, ADR-012).</li>
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
     * @return the matching public key, or {@code null} when the {@code kid} is unknown
     * @throws eu.exeris.kernel.spi.exceptions.security.SecurityAuthenticationException
     *         on terminal rotation / staleness deny
     */
    RSAPublicKey resolve(String kid);
}
