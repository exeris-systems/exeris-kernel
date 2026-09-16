/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.spi.security;

/**
 * Canonical kernel-owned role-name → bit-position mapping for the small set of
 * system roles every Exeris deployment understands.
 *
 * <h2>Why a fixed mapping</h2>
 * <p>Per ADR-014 §3 the kernel's {@code @RequiresRole} pipeline is built around a
 * single primitive bitmask AND/EQ on the hot path. System roles get reserved low
 * bits so that the runtime check never has to consult an indirection table for
 * the most common authorization primitives ({@code ROLE_ADMIN}, etc.).
 * Application-defined roles are assigned remaining bits at build time by the APT
 * processor and recorded in the generated {@code RoleCheckRegistry}.
 *
 * <h2>Bit budget</h2>
 * <p>The runtime check uses a {@code long} mask (64 bits). System roles occupy
 * bits {@code [0..7]} (8 reserved slots, today 4 used) leaving 56 bits for
 * application roles. ADR-014 §10 (Consequences) covers shard expansion if a
 * deployment ever exceeds 64 roles.
 *
 * @since 0.7
 * @see RequiresRole
 * @see <a href="../../../../../../docs/adr/ADR-014-requiresrole-compile-time-rbac-generation.md">ADR-014</a>
 */
public final class KernelRoles {

    // -----------------------------------------------------------------------
    // System role bit positions (reserved range: [0, SYSTEM_ROLE_BIT_LIMIT))
    // -----------------------------------------------------------------------

    /** Internal kernel components (bootstrap, scheduler, recovery). Bit 0. */
    public static final int BIT_SYSTEM = 0;

    /** Operator with full administrative authority. Bit 1. */
    public static final int BIT_ADMIN = 1;

    /** Operator with read-mostly observability + maintenance authority. Bit 2. */
    public static final int BIT_OPERATOR = 2;

    /** Authenticated user with default tenant-scoped access. Bit 3. */
    public static final int BIT_USER = 3;

    /**
     * Exclusive upper bound of the system-role bit range. Application-defined
     * roles assigned by the APT processor MUST start at this index or higher.
     */
    public static final int SYSTEM_ROLE_BIT_LIMIT = 8;

    // -----------------------------------------------------------------------
    // Canonical role-name constants — these strings appear in @RequiresRole
    // -----------------------------------------------------------------------

    /** Canonical name of the internal kernel role, resolved to {@link #BIT_SYSTEM}. */
    public static final String ROLE_SYSTEM = "ROLE_SYSTEM";

    /** Canonical name of the full-authority operator role, resolved to {@link #BIT_ADMIN}. */
    public static final String ROLE_ADMIN = "ROLE_ADMIN";

    /** Canonical name of the read-mostly operator role, resolved to {@link #BIT_OPERATOR}. */
    public static final String ROLE_OPERATOR = "ROLE_OPERATOR";

    /** Canonical name of the default authenticated-user role, resolved to {@link #BIT_USER}. */
    public static final String ROLE_USER = "ROLE_USER";

    private KernelRoles() {
        // Constants holder — not instantiable.
    }

    /**
     * Returns the canonical bit position for a system role, or {@code -1} if the
     * given name is not one of the system roles defined here.
     *
     * @param roleName non-null role name
     * @return bit position {@code [0, SYSTEM_ROLE_BIT_LIMIT)} or {@code -1}
     * @apiNote This lookup exists for the APT processor's compile-time bit resolution. Runtime
     *          decision paths MUST use the precomputed bitmask in the generated
     *          {@code RoleCheckRegistry} instead of calling this per request.
     */
    public static int systemRoleBit(String roleName) {
        return switch (roleName) {
            case ROLE_SYSTEM -> BIT_SYSTEM;
            case ROLE_ADMIN -> BIT_ADMIN;
            case ROLE_OPERATOR -> BIT_OPERATOR;
            case ROLE_USER -> BIT_USER;
            case null, default -> -1;
        };
    }

    /**
     * Returns the canonical bitmask for a single system role. Convenience wrapper
     * over {@link #systemRoleBit(String)}.
     *
     * @param roleName non-null role name
     * @return bitmask with one bit set, or {@code 0L} if the role is unknown
     */
    public static long systemRoleMask(String roleName) {
        int bit = systemRoleBit(roleName);
        return bit < 0 ? 0L : 1L << bit;
    }
}
