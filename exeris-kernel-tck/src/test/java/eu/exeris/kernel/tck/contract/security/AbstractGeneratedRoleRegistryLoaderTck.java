/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.tck.contract.security;

import eu.exeris.kernel.spi.security.ImmutablePrincipal;
import eu.exeris.kernel.spi.security.PrincipalContext;
import eu.exeris.kernel.spi.security.RoleRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * TCK: contract for the reflective loader that adapts the build-time generated
 * {@code RoleCheckRegistry} into the {@link RoleRegistry} SPI (ADR-014 §3).
 *
 * <h2>Contract</h2>
 * <ul>
 *   <li><b>Absent</b> — when no generated class is present the loader returns a
 *       fail-closed empty registry: {@code methodCount() == 0}, every required
 *       mask {@code 0L}, {@code matchIsAll == false}, {@code roleNameToBit == -1}.</li>
 *   <li><b>Present</b> — when a generated class is present its five static
 *       accessors round-trip through the SPI: {@code methodCount() > 0}, masks
 *       and {@code roleNameToBit} resolve.</li>
 *   <li><b>Fail-closed</b> — the empty registry combined with any methodId via
 *       the binding's enforcer denies (no allow-all).</li>
 *   <li>The loader never throws at bootstrap, regardless of class presence.</li>
 * </ul>
 *
 * <h2>How to use</h2>
 * <p>Bindings supply three factories: {@link #loadAbsent()} (loader result when
 * no generated class is on the classpath), {@link #loadPresent()} (loader result
 * adapted from a present generated class fixture), and {@link #isAllowed} (the
 * binding's allocation-free enforcer delegate). The abstract drives the
 * contract; the binding owns the Core loader and the generated fixture.
 *
 * @since 0.8.0
 */
public abstract class AbstractGeneratedRoleRegistryLoaderTck {

    /**
     * Returns the registry the loader yields when <em>no</em> generated
     * {@code RoleCheckRegistry} is resolvable (the common case). Must be the
     * fail-closed empty registry.
     *
     * @return non-null empty registry
     */
    protected abstract RoleRegistry loadAbsent();

    /**
     * Returns the registry the loader yields when a generated
     * {@code RoleCheckRegistry} fixture <em>is</em> resolvable. The fixture MUST
     * declare at least one {@code @RequiresRole} entry point so the accessors
     * are non-trivial.
     *
     * @return non-null registry backed by a present generated class
     */
    protected abstract RoleRegistry loadPresent();

    /**
     * Returns the allocation-free decision routine ({@code RoleCheckEnforcer}).
     *
     * @param methodId  compile-time method id
     * @param principal non-null principal
     * @param registry  non-null registry
     * @return {@code true} when permitted
     */
    protected abstract boolean isAllowed(int methodId, PrincipalContext principal, RoleRegistry registry);

    @Test
    @DisplayName("Absent generated class yields the fail-closed empty registry")
    void absentYieldsEmpty() {
        RoleRegistry empty = loadAbsent();

        assertThat(empty).as("loader must never return null").isNotNull();
        assertThat(empty.methodCount()).isZero();
        assertThat(empty.requiredAny(0)).isZero();
        assertThat(empty.requiredAll(0)).isZero();
        assertThat(empty.matchIsAll(0)).isFalse();
        assertThat(empty.roleNameToBit("ROLE_ADMIN")).isEqualTo(-1);
        assertThat(empty.roleNameToMask("ROLE_ADMIN")).isZero();
    }

    @Test
    @DisplayName("Empty registry + any methodId denies via the enforcer (fail-closed)")
    void emptyRegistryFailsClosed() {
        RoleRegistry empty = loadAbsent();
        PrincipalContext admin = ImmutablePrincipal.system(UUID.randomUUID(), Set.of("ROLE_ADMIN"));

        // ImmutablePrincipal keeps roleMask()==0L; even if it didn't, requiredAny==0L denies.
        assertThat(isAllowed(0, admin, empty))
                .as("empty registry must deny — (mask & 0L) != 0L is always false")
                .isFalse();
        assertThat(isAllowed(7, admin, empty)).isFalse();
    }

    @Test
    @DisplayName("Present generated class round-trips its accessors through the SPI")
    void presentRoundTrips() {
        RoleRegistry registry = loadPresent();

        assertThat(registry).isNotNull();
        assertThat(registry.methodCount())
                .as("fixture must declare at least one @RequiresRole entry point")
                .isPositive();
        // ROLE_ADMIN is a canonical system role at bit 1 in both the processor and
        // the generated fixture, so the round-trip is deterministic.
        assertThat(registry.roleNameToBit("ROLE_ADMIN"))
                .as("ROLE_ADMIN resolves to its canonical system bit")
                .isEqualTo(1);
        assertThat(registry.roleNameToMask("ROLE_ADMIN")).isEqualTo(1L << 1);
        assertThat(registry.roleNameToBit("ROLE_GHOST"))
                .as("unknown roles surface as -1 so callers fail closed")
                .isEqualTo(-1);
        // All five SPI accessors must round-trip through the reflective binding,
        // not only the ANY/roleName pair. Method 0 of the fixture is an ANY method,
        // so its ALL mask is 0L and matchIsAll is false — assert both so a binding
        // that mis-wired the requiredAll/matchIsAll MethodHandles is caught.
        assertThat(registry.requiredAny(0))
                .as("ANY mask round-trips to ROLE_ADMIN's single bit")
                .isEqualTo(1L << 1);
        assertThat(registry.requiredAll(0))
                .as("an ANY method has an empty ALL mask")
                .isZero();
        assertThat(registry.matchIsAll(0))
                .as("an ANY method must report matchIsAll == false")
                .isFalse();
        // Method 0 of the fixture requires ROLE_ADMIN (ANY): a principal masked
        // for ROLE_ADMIN is allowed, an empty-mask principal is denied.
        PrincipalContext adminMask = maskOnly(registry.roleNameToMask("ROLE_ADMIN"));
        PrincipalContext noMask = maskOnly(0L);
        assertThat(isAllowed(0, adminMask, registry)).isTrue();
        assertThat(isAllowed(0, noMask, registry)).isFalse();
    }

    @Test
    @DisplayName("Present registry with a method-id out of range fails fast, never allows")
    void presentRegistryMethodIdOutOfRangeFailsFast() {
        RoleRegistry registry = loadPresent();
        int outOfRange = registry.methodCount(); // first index past the compiled table

        // ADVERSARIAL: a method-id beyond the compiled table is a programming/wiring
        // error. The reflective registry indexes a backing array, so the bound
        // MethodHandle propagates the ArrayIndexOutOfBoundsException unchanged. The
        // contract pins fail-FAST (a thrown RuntimeException), never a silent
        // permissive mask that a fail-open regression might return.
        PrincipalContext adminMask = maskOnly(registry.roleNameToMask("ROLE_ADMIN"));
        org.assertj.core.api.Assertions
                .assertThatThrownBy(() -> isAllowed(outOfRange, adminMask, registry))
                .as("out-of-range method-id must surface as a thrown error, never an implicit allow")
                .isInstanceOf(RuntimeException.class);
    }

    private static PrincipalContext maskOnly(long mask) {
        return new MaskOnlyPrincipal(mask);
    }

    private record MaskOnlyPrincipal(long maskValue) implements PrincipalContext {
        @Override
        public UUID principalId() {
            return new UUID(0L, 0L);
        }

        @Override
        public java.util.Optional<UUID> tenantId() {
            return java.util.Optional.empty();
        }

        @Override
        public Set<String> roles() {
            return Set.of();
        }

        @Override
        public Set<String> scopes() {
            return Set.of();
        }

        @Override
        public long roleMask() {
            return maskValue;
        }
    }
}
