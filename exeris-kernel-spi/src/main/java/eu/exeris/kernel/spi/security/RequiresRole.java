/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.spi.security;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Compile-time RBAC declaration: the annotated method or type entry point requires
 * the caller's principal to hold the listed role(s).
 *
 * <h2>Why {@link RetentionPolicy#SOURCE}</h2>
 * <p>Per ADR-014 §3 the annotation is consumed exclusively at compile time by the
 * APT processor that ships in {@code exeris-kernel-build-config}. The processor
 * produces a generated {@code RoleCheckRegistry} with primitive-only static fields
 * and an O(1) lookup per method-id; no runtime reflection ever inspects this
 * annotation. {@code SOURCE} retention guarantees the annotation never reaches the
 * class file and therefore cannot leak onto a hot path through {@code Class
 * .getAnnotation()} misuse.
 *
 * <h2>Match semantics</h2>
 * <p>{@link #match()} controls how the declared roles are combined into a single
 * bitmask check at runtime:
 *
 * <pre>{@code
 * @RequiresRole({"ROLE_ADMIN"})                       // any admin → permitted
 * @RequiresRole({"ROLE_ADMIN", "ROLE_OPERATOR"})      // admin OR operator
 * @RequiresRole(value = {"ROLE_ADMIN", "ROLE_AUDITOR"}, match = RoleMatch.ALL)
 *                                                     // both required
 * }</pre>
 *
 * <h2>Coexistence with {@code CitadelGuard}</h2>
 * <p>{@code CitadelGuard.requireRole(String)} remains the runtime fallback for
 * dynamic role decisions (the required role is computed from request data).
 * Static {@code @RequiresRole} checks short-circuit before {@code CitadelGuard}
 * is consulted; both produce {@code EX-SEC-2003} on denial so operators see
 * uniform telemetry.
 *
 * <h2>Build-time validation</h2>
 * <p>The APT processor MUST fail compilation when {@link #value()} contains a
 * role name that is not present in the canonical {@link KernelRoles} table or in
 * the application-defined role mapping resolved at build time. Build failures
 * are preferred over runtime warnings — this is a security contract, not a
 * style preference.
 *
 * @see KernelRoles
 * @see RoleMatch
 * @see <a href="../../../../../../docs/adr/ADR-014-requiresrole-compile-time-rbac-generation.md">ADR-014</a>
 * @since 0.7.0
 */
@Retention(RetentionPolicy.SOURCE)
@Target({ElementType.METHOD, ElementType.TYPE})
public @interface RequiresRole {

    /**
     * Required role names. Must be non-empty. Each entry must resolve to a bit
     * assignment known to the APT processor — either a {@link KernelRoles} system
     * role or an application-declared role.
     *
     * @return required role names; never empty
     */
    String[] value();

    /**
     * How {@link #value()} entries are combined when more than one role is listed.
     * Defaults to {@link RoleMatch#ANY} so single-role declarations carry no
     * surprise ({@code @RequiresRole("ROLE_ADMIN")} is equivalent to ANY-of).
     *
     * @return match strategy; default {@link RoleMatch#ANY}
     */
    RoleMatch match() default RoleMatch.ANY;
}
