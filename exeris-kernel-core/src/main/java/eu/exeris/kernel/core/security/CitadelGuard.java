/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.core.security;

import eu.exeris.kernel.core.security.jfr.SecurityJfrEvents;
import eu.exeris.kernel.spi.context.KernelProviders;
import eu.exeris.kernel.spi.exceptions.security.InsufficientPrivilegesException;
import eu.exeris.kernel.spi.security.PrincipalContext;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Core: Stateless RBAC enforcement gate for the L1 Citadel boundary.
 *
 * <h2>Sentinel Pattern (Zero-Allocation on hot path)</h2>
 * <p>For every unique {@code requiredRole} string seen during bootstrap warm-up,
 * {@code CitadelGuard} pre-allocates an {@link InsufficientPrivilegesException}
 * Sentinel — a stack-trace-free, heap-stable instance. On the hot path, throwing
 * a Sentinel costs exactly zero heap allocations: no {@code fillInStackTrace()},
 * no {@code UUID.randomUUID()}, no {@code Instant.now()}.
 *
 * <h2>Usage</h2>
 * {@snippet lang="java" :
 * CitadelGuard guard = new CitadelGuard();
 *
 * // During bootstrap warm-up — pre-allocate sentinels for known roles:
 * guard.preAllocate("ROLE_ADMIN");
 * guard.preAllocate("ROLE_TENANT_WRITER");
 *
 * // On every request hot path — O(1), zero-alloc for known roles:
 * guard.requireRole("ROLE_ADMIN");
 * }
 *
 * <h2>The Wall</h2>
 * <p>Imports only {@code exeris-kernel-spi}. No JWT, no Spring Security,
 * no concrete implementation detail crosses this boundary.
 *
 * @since 0.5
 * @see InsufficientPrivilegesException
 * @see eu.exeris.kernel.spi.security.PrincipalContext#hasRole(String)
 */
public final class CitadelGuard {

    private static final VarHandle SEALED;

    static {
        try {
            SEALED = MethodHandles.lookup()
                    .findVarHandle(CitadelGuard.class, "sealedFlag", boolean.class);
        } catch (NoSuchFieldException | IllegalAccessException ex) {
            throw new ExceptionInInitializerError(ex);
        }
    }

    /**
     * Pre-allocated Sentinel exception pool, keyed by required role string.
     *
     * <p>{@link ConcurrentHashMap} is safe here: populated once during bootstrap
     * warm-up (write phase) and then read-only on the hot path (read phase).
     * The map is effectively immutable after {@link #seal()} is called.
     */
    private final Map<String, InsufficientPrivilegesException> sentinelPool =
            new ConcurrentHashMap<>(16);

    @SuppressWarnings("unused")
    private volatile boolean sealedFlag;

    /**
     * Pre-allocates a Sentinel {@link InsufficientPrivilegesException} for the given role.
     *
     * <p>Call this once per known role during bootstrap warm-up. Subsequent calls
     * to {@link #requireRole(String)} for pre-allocated roles will throw the
     * cached Sentinel — zero heap allocation on the hot path.
     *
     * @param requiredRole the role identifier to pre-allocate a sentinel for
     * @throws IllegalStateException if {@link #seal()} has already been called;
     *         sentinel pools must not be mutated after the kernel transitions to READY
     */
    public void preAllocate(String requiredRole) {
        if ((boolean) SEALED.getVolatile(this)) {
            throw new IllegalStateException(
                    "CitadelGuard is sealed — preAllocate() must not be called after kernel reaches READY state");
        }
        sentinelPool.computeIfAbsent(requiredRole, InsufficientPrivilegesException::sentinel);
    }

    /**
     * Seals the sentinel pool, permanently preventing further {@link #preAllocate(String)} calls.
     *
     * <p>Call once after bootstrap warm-up completes and before the kernel transitions
     * to the READY state and begins serving requests on the hot path. Uses a
     * {@link VarHandle} CAS to ensure the transition is visible to all virtual threads
     * without additional synchronization overhead.
     *
     * <p>This method is idempotent — calling it multiple times has no additional effect.
     */
    public void seal() {
        SEALED.setVolatile(this, true);
    }

    /**
     * Returns {@code true} if this guard's sentinel pool has been sealed.
     *
     * @return {@code true} after {@link #seal()} has been called
     */
    public boolean isSealed() {
        return (boolean) SEALED.getVolatile(this);
    }

    /**
     * Enforces that the current {@link PrincipalContext} (read from
     * {@link KernelProviders#PRINCIPAL_CONTEXT}) holds the specified role.
     *
     * <h4>Fail-Fast RBAC</h4>
     * <p>If the principal lacks the role, an {@link InsufficientPrivilegesException}
     * is thrown. For pre-allocated roles (via {@link #preAllocate(String)}), the
     * Sentinel instance is thrown — zero heap allocation.
     *
     * <h4>JFR Telemetry</h4>
     * <p>A {@code InsufficientPrivileges} JFR event is emitted on rejection.
     * The event carries the value-based {@code UUID.hashCode()} of the principalId —
     * never the raw UUID.
     *
     * @param requiredRole the role the principal must hold
     * @throws InsufficientPrivilegesException ({@code EX-SEC-2003}) if the principal
     *         lacks {@code requiredRole}
     * @throws eu.exeris.kernel.spi.exceptions.security.PrincipalContextMissingException
     *         ({@code EX-SEC-2001}) if no {@link PrincipalContext} is bound in the current scope
     */
    public void requireRole(String requiredRole) {
        PrincipalContext principal = KernelProviders.principal();

        if (!principal.hasRole(requiredRole)) {
            SecurityJfrEvents.emitInsufficientPrivileges(
                    requiredRole,
                    principal.principalId().hashCode()
            );
            InsufficientPrivilegesException sentinel = sentinelPool.get(requiredRole);
            if (sentinel != null) {
                throw sentinel;
            }
            throw new InsufficientPrivilegesException(requiredRole);
        }
    }

    /**
     * Checks whether the current {@link PrincipalContext} holds the specified role,
     * without throwing. Useful for conditional logic rather than hard enforcement.
     *
     * @param role the role to check
     * @return {@code true} if the principal holds the role; {@code false} otherwise
     * @throws eu.exeris.kernel.spi.exceptions.security.PrincipalContextMissingException
     *         ({@code EX-SEC-2001}) if no {@link PrincipalContext} is bound in the current scope
     */
    public boolean hasRole(String role) {
        return KernelProviders.principal().hasRole(role);
    }

    /**
     * Returns the number of pre-allocated Sentinel instances in the pool.
     *
     * <p>Useful for bootstrap verification — ensures all expected roles were
     * pre-allocated before the kernel transitions to the hot path.
     *
     * @return number of pre-allocated sentinels
     */
    public int preAllocatedCount() {
        return sentinelPool.size();
    }
}
