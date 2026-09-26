/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
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
 * {@snippet lang="java" :
 *   @RequiresRole({"ROLE_ADMIN"})                       // any admin → permitted
 *   @RequiresRole({"ROLE_ADMIN", "ROLE_OPERATOR"})      // admin OR operator
 *   @RequiresRole(value = {"ROLE_ADMIN", "ROLE_AUDITOR"}, match = RoleMatch.ALL)
 *                                                       // both required
 * }
 *
 * @implSpec The APT processor assigns any role name absent from the canonical {@link KernelRoles}
 *           table a fresh application-scoped bit in {@code [8, 64)}, alphabetically, at build
 *           time. It fails compilation only when {@link #value()} is empty or the application-role
 *           bit budget (56 roles) is exceeded — never for an unrecognised role name, because no
 *           such rejection exists.
 * @apiNote  {@code CitadelGuard.requireRole(String)} is the runtime companion for dynamic role
 *           decisions, where the required role is computed from request data. A static
 *           {@code @RequiresRole} check short-circuits before {@code CitadelGuard} is consulted,
 *           and both raise {@code EX-SEC-2003} on denial so operators see one telemetry shape for
 *           both paths.
 * @since 0.7
 * @see KernelRoles
 * @see RoleMatch
 * @see <a href="../../../../../../docs/adr/ADR-014-requiresrole-compile-time-rbac-generation.md">ADR-014</a>
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
