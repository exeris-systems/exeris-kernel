/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.core.security;

import eu.exeris.kernel.spi.exceptions.security.InsufficientPrivilegesException;
import eu.exeris.kernel.spi.security.PrincipalContext;
import eu.exeris.kernel.spi.security.RoleRegistry;

/**
 * Runtime decision helper for {@code @RequiresRole}-annotated entry points.
 *
 * <p>Per ADR-014 §5 the authorization decision is a single primitive
 * AND/EQ between the principal's pre-computed {@code long} mask and the
 * registry-supplied required mask:
 *
 * {@snippet lang="java" :
 * RoleMatch.ANY  →  (principal.roleMask() & registry.requiredAny(methodId)) != 0L
 * RoleMatch.ALL  →  (principal.roleMask() & registry.requiredAll(methodId)) == registry.requiredAll(methodId)
 * }
 *
 * <p>The accept path is allocation-free. The deny path raises
 * {@link InsufficientPrivilegesException} ({@code EX-SEC-2003}) with a
 * synthetic role label so operators see the same telemetry shape as the
 * existing dynamic {@code CitadelGuard.requireRole(...)} fallback.
 *
 * <h2>Wiring</h2>
 * <p>The intended call sites are HTTP/transport admission interceptors that
 * map a request entry point to a stable {@code methodId} (typically the
 * compile-time constant emitted by the {@code RequiresRoleProcessor}). The
 * registry instance is constructor-injected by the operator's bootstrap;
 * tests inject a {@link RoleRegistry} stub.
 *
 * @since 0.7
 */
public final class RoleCheckEnforcer {

    private RoleCheckEnforcer() {
        // Static helper — not instantiable.
    }

    /**
     * Returns {@code true} when the principal's mask satisfies the
     * registry-declared requirement for {@code methodId}. Allocation-free.
     *
     * @param methodId  compile-time method id
     * @param principal non-null principal context
     * @param registry  non-null role registry
     * @return {@code true} when the request is permitted
     */
    public static boolean isAllowed(int methodId, PrincipalContext principal, RoleRegistry registry) {
        long principalMask = principal.roleMask();
        if (registry.matchIsAll(methodId)) {
            long required = registry.requiredAll(methodId);
            return (principalMask & required) == required;
        }
        return (principalMask & registry.requiredAny(methodId)) != 0L;
    }

    /**
     * Enforces the registered requirement; raises {@link InsufficientPrivilegesException}
     * on deny.
     *
     * @param methodId  compile-time method id
     * @param principal non-null principal context
     * @param registry  non-null role registry
     * @throws InsufficientPrivilegesException ({@code EX-SEC-2003}) when the principal
     *         lacks the required role(s)
     */
    public static void check(int methodId, PrincipalContext principal, RoleRegistry registry) {
        if (!isAllowed(methodId, principal, registry)) {
            throw new InsufficientPrivilegesException(
                    "@RequiresRole[methodId=" + methodId + "]");
        }
    }
}
