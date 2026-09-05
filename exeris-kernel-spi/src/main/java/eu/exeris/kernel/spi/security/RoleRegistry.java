/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.spi.security;

/**
 * Read-only SPI contract over a build-time role-check registry per ADR-014 §3.
 *
 * <h2>Why an interface</h2>
 * <p>The canonical implementation is the generated
 * {@code eu.exeris.kernel.security.generated.RoleCheckRegistry} class produced
 * by the {@code RequiresRoleProcessor}. That class is intentionally final with
 * static accessors so its hot-path lookups inline. This interface adapts those
 * static accessors into an injectable contract that {@code RoleCheckEnforcer},
 * test harnesses, and operator-supplied registry sources can all share.
 *
 * <h2>Hot-path discipline</h2>
 * <p>Implementations MUST satisfy:
 * <ul>
 *   <li>{@link #requiredAny(int)} / {@link #requiredAll(int)} — O(1), allocation-free.</li>
 *   <li>{@link #matchIsAll(int)} — O(1), allocation-free.</li>
 *   <li>{@link #methodCount()} — O(1).</li>
 *   <li>{@link #roleNameToBit(String)} — O(1) average; called at authentication
 *       time, never on the per-request hot path.</li>
 * </ul>
 *
 * @since 0.7
 */
public interface RoleRegistry {

    /**
     * Returns the {@code RoleMatch.ANY}-shaped required bitmask for the given
     * method id, or {@code 0L} if the method is annotated with
     * {@code RoleMatch.ALL}.
     *
     * @param methodId compile-time method id from the generated registry
     * @return required {@code ANY} mask
     */
    long requiredAny(int methodId);

    /**
     * Returns the {@code RoleMatch.ALL}-shaped required bitmask for the given
     * method id, or {@code 0L} if the method is annotated with
     * {@code RoleMatch.ANY}.
     *
     * @param methodId compile-time method id from the generated registry
     * @return required {@code ALL} mask
     */
    long requiredAll(int methodId);

    /**
     * @param methodId compile-time method id from the generated registry
     * @return {@code true} when the method declared {@code RoleMatch.ALL}
     */
    boolean matchIsAll(int methodId);

    /**
     * @return number of {@code @RequiresRole}-annotated entry points compiled
     *         into the registry
     */
    int methodCount();

    /**
     * Resolves a role name to its compile-time bit position. Returns {@code -1}
     * when the role is unknown so callers fail closed.
     *
     * @param roleName non-null role name
     * @return bit position {@code [0, 64)} or {@code -1}
     */
    int roleNameToBit(String roleName);

    /**
     * Convenience: returns a single-bit mask for {@code roleName} or {@code 0L}
     * when the role is unknown to this registry.
     *
     * @param roleName non-null role name
     * @return single-bit mask, or {@code 0L}
     */
    default long roleNameToMask(String roleName) {
        int bit = roleNameToBit(roleName);
        return bit < 0 ? 0L : 1L << bit;
    }
}
