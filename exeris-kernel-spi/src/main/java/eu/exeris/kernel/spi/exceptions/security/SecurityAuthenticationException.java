/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.spi.exceptions.security;

import eu.exeris.kernel.spi.exceptions.ExerisKernelException;
import eu.exeris.kernel.spi.exceptions.KernelErrorCodes;

/**
 * Thrown when token validation fails (expired, malformed, or revoked).
 *
 * <h2>Error Code</h2>
 * <p>{@link KernelErrorCodes#EX_SEC_2002} — token validation failure.
 *
 * <h2>rawArgs layout (Glass-Box Telemetry)</h2>
 * <ul>
 *   <li>index 0 – {@code String} tokenType (e.g. "JWT", "OPAQUE")</li>
 *   <li>index 1 – {@code String} failureReason (e.g. "expired", "malformed", "revoked")</li>
 * </ul>
 *
 * @since 0.5.0
 */
public final class SecurityAuthenticationException extends ExerisKernelException {

    /**
     * Creates a new authentication exception.
     *
     * @param tokenType     the type of token that failed validation
     * @param failureReason a short reason code (no user data — safe for telemetry)
     */
    public SecurityAuthenticationException(String tokenType, String failureReason) {
        super(KernelErrorCodes.EX_SEC_2002, "Token validation failed", null,
                tokenType, failureReason);
    }

    /**
     * Creates a new authentication exception with a root cause.
     *
     * @param tokenType     the type of token that failed validation
     * @param failureReason a short reason code
     * @param cause         root cause (e.g., signature verification failure)
     */
    public SecurityAuthenticationException(String tokenType, String failureReason, Throwable cause) {
        super(KernelErrorCodes.EX_SEC_2002, "Token validation failed", cause,
                tokenType, failureReason);
    }
}

