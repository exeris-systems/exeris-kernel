/*
 * Copyright (C) 2025-2026 Exeris. All rights reserved.
 *
 * This code is part of the Exeris Systems.
 * Distributed under the proprietary Exeris Software License.
 * Unauthorized copying or distribution is prohibited.
 */
package eu.exeris.kernel.spi.exceptions.security;

import eu.exeris.kernel.spi.exceptions.ExerisKernelException;
import eu.exeris.kernel.spi.exceptions.KernelErrorCodes;

/**
 * Thrown when a {@link eu.exeris.kernel.spi.security.PrincipalContext} lacks a required
 * role, rejected by the L1 Citadel RBAC gate before any business logic is reached.
 *
 * <h2>Error Code</h2>
 * <p>{@link KernelErrorCodes#EX_SEC_2003} — insufficient privileges.
 *
 * <h2>Sentinel Pattern</h2>
 * <p>On the hot path, code SHOULD NOT allocate a new instance per rejected request.
 * Use {@link eu.exeris.kernel.core.security.CitadelGuard} which holds pre-allocated
 * Sentinel instances (stack-trace disabled) to honour the zero-allocation contract.
 *
 * <h2>rawArgs layout (Glass-Box Telemetry)</h2>
 * <ul>
 *   <li>index 0 – {@code String} requiredRole — the role that was missing</li>
 * </ul>
 *
 * @since 0.5.0
 * @see eu.exeris.kernel.spi.security.PrincipalContext#hasRole(String)
 */
public final class InsufficientPrivilegesException extends ExerisKernelException {

    /**
     * Creates a new insufficient-privileges exception (diagnostic path — allocates stack trace).
     *
     * @param requiredRole the role that the principal was missing
     */
    public InsufficientPrivilegesException(String requiredRole) {
        super(KernelErrorCodes.EX_SEC_2003,
                "Principal lacks required role — request rejected at L1 Citadel boundary",
                null,
                requiredRole);
    }

    @SuppressWarnings({"PMD.UseVarargs", "PMD.UnusedFormalParameter"})
    private InsufficientPrivilegesException(String requiredRole,
                                            boolean enableSuppression,
                                            boolean writableStackTrace,
                                            Object[] rawArgs) {
        super(KernelErrorCodes.EX_SEC_2003,
                "Principal lacks required role — request rejected at L1 Citadel boundary",
                null,
                enableSuppression,
                writableStackTrace,
                rawArgs);
    }

    /**
     * Creates a pre-allocated, stack-trace-free Sentinel instance for the given role.
     *
     * <p>The Sentinel is intended to be stored as a static constant and re-thrown
     * on every RBAC rejection for that role — zero heap allocation per rejection.
     * The returned instance has {@code writableStackTrace=false} so that
     * {@link Throwable#fillInStackTrace()} is never called.
     *
     * @param requiredRole the role that the principal was missing
     * @return Sentinel instance; callers MUST store this as a static constant
     */
    public static InsufficientPrivilegesException sentinel(String requiredRole) {
        return new InsufficientPrivilegesException(
                requiredRole, false, false, new Object[]{requiredRole});
    }
}




