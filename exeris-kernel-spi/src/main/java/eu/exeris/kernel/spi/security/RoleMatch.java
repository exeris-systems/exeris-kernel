/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.spi.security;

/**
 * Match semantics for {@link RequiresRole} declarations.
 *
 * <p>Per ADR-014 §3, the generated runtime check translates to a single primitive
 * operation:
 *
 * <ul>
 *   <li>{@link #ANY} — {@code (principal.roleMask() & required) != 0L}</li>
 *   <li>{@link #ALL} — {@code (principal.roleMask() & required) == required}</li>
 * </ul>
 *
 * <p>No allocation, no reflection, no string interning at decision time.
 *
 * @since 0.7
 */
public enum RoleMatch {

    /** Principal must hold at least one of the declared roles. */
    ANY,

    /** Principal must hold every declared role. */
    ALL
}
