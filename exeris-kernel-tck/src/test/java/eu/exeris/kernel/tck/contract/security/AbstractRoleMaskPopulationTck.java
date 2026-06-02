/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.tck.contract.security;

import eu.exeris.kernel.spi.context.KernelProviders;
import eu.exeris.kernel.spi.security.ImmutablePrincipal;
import eu.exeris.kernel.spi.security.PrincipalContext;
import eu.exeris.kernel.spi.security.RoleRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * TCK: contract for role-mask population at authentication time (ADR-014 §5).
 *
 * <h2>Contract</h2>
 * <ul>
 *   <li>When the active {@link RoleRegistry} carries {@code @RequiresRole} entry
 *       points ({@code methodCount() > 0}), the {@code SecurityInterceptor}
 *       resolves the authenticated principal's role names into a precomputed
 *       {@code roleMask()} and binds it into
 *       {@link KernelProviders#PRINCIPAL_CONTEXT}.</li>
 *   <li>When the registry is empty, the original principal is bound unchanged —
 *       {@code roleMask()} stays {@code 0L} and the principal is NOT wrapped.</li>
 * </ul>
 *
 * <h2>How to use</h2>
 * <p>The binding supplies {@link #runIntercept}: build a {@code SecurityInterceptor}
 * over a stub success provider returning {@code principal}, with the given
 * {@code registry}, run its {@code intercept(...)} and invoke {@code handler}
 * within the authenticated scope. The abstract's handler reads
 * {@link KernelProviders#PRINCIPAL_CONTEXT} to observe the bound mask.
 *
 * @since 0.8.0
 */
public abstract class AbstractRoleMaskPopulationTck {

    /** Canonical system bit for {@code ROLE_ADMIN} (mirrors the processor). */
    protected static final int BIT_ADMIN = 1;

    /**
     * Builds a {@code SecurityInterceptor} over a stub success provider that
     * authenticates to {@code principal} with the supplied {@code registry},
     * runs {@code intercept(...)}, and invokes {@code handler} inside the
     * authenticated scope (where {@link KernelProviders#PRINCIPAL_CONTEXT} is
     * bound).
     *
     * @param registry  active role registry
     * @param principal principal returned by the stub provider
     * @param handler   logic to run in the authenticated scope
     * @return {@code true} if the handler was invoked
     */
    protected abstract boolean runIntercept(RoleRegistry registry,
                                            PrincipalContext principal,
                                            Runnable handler);

    @Test
    @DisplayName("Registry with methods populates roleMask from the principal's role names")
    void populatesMaskWhenRegistryNonEmpty() {
        RoleRegistry registry = new SingleMethodAdminRegistry();
        PrincipalContext principal = ImmutablePrincipal.system(UUID.randomUUID(), Set.of("ROLE_ADMIN"));
        AtomicReference<PrincipalContext> bound = new AtomicReference<>();

        boolean invoked = runIntercept(registry, principal,
                () -> bound.set(KernelProviders.PRINCIPAL_CONTEXT.get()));

        assertThat(invoked).isTrue();
        assertThat(bound.get()).as("a principal must be bound in scope").isNotNull();
        assertThat(bound.get().roleMask())
                .as("roleMask must equal the registry's single-bit mask for ROLE_ADMIN")
                .isEqualTo(1L << BIT_ADMIN);
        assertThat(bound.get().roles())
                .as("delegated accessors must still surface the original role names")
                .containsExactly("ROLE_ADMIN");
    }

    @Test
    @DisplayName("Unknown role names contribute 0 to the mask (fail-closed, no -1 garbage)")
    void unknownRolesContributeZero() {
        RoleRegistry registry = new SingleMethodAdminRegistry();
        // ADVERSARIAL: ROLE_GHOST is unknown to the registry (roleNameToBit == -1).
        // The population loop must OR in its single-bit mask of 0L, never a garbage
        // bit derived from a -1 shift. The resulting mask must be exactly ADMIN's bit.
        PrincipalContext principal = ImmutablePrincipal.system(
                UUID.randomUUID(), Set.of("ROLE_ADMIN", "ROLE_GHOST"));
        AtomicReference<PrincipalContext> bound = new AtomicReference<>();

        boolean invoked = runIntercept(registry, principal,
                () -> bound.set(KernelProviders.PRINCIPAL_CONTEXT.get()));

        assertThat(invoked).isTrue();
        assertThat(bound.get().roleMask())
                .as("unknown role must contribute 0, leaving only ROLE_ADMIN's bit set")
                .isEqualTo(1L << BIT_ADMIN);
    }

    @Test
    @DisplayName("Masked accessors delegate every field to the wrapped principal")
    void wrappedPrincipalDelegatesAllAccessors() {
        RoleRegistry registry = new SingleMethodAdminRegistry();
        UUID principalId = UUID.randomUUID();
        UUID tenantId = UUID.randomUUID();
        // Tenant + scopes populated so a wrapper that only forwarded roleMask()/roles()
        // but dropped tenantId()/scopes() (broken delegate) is caught.
        PrincipalContext principal = ImmutablePrincipal.ofTenant(
                principalId, tenantId, Set.of("ROLE_ADMIN"), Set.of("read:orders", "write:orders"));
        AtomicReference<PrincipalContext> bound = new AtomicReference<>();

        boolean invoked = runIntercept(registry, principal,
                () -> bound.set(KernelProviders.PRINCIPAL_CONTEXT.get()));

        assertThat(invoked).isTrue();
        PrincipalContext masked = bound.get();
        assertThat(masked)
                .as("a non-empty registry with resolvable roles must wrap the principal")
                .isNotSameAs(principal);
        assertThat(masked.roleMask()).isEqualTo(1L << BIT_ADMIN);
        assertThat(masked.principalId()).as("principalId must delegate").isEqualTo(principalId);
        assertThat(masked.tenantId()).as("tenantId must delegate").contains(tenantId);
        assertThat(masked.roles()).as("roles must delegate").containsExactly("ROLE_ADMIN");
        assertThat(masked.scopes()).as("scopes must delegate")
                .containsExactlyInAnyOrder("read:orders", "write:orders");
    }

    @Test
    @DisplayName("A pre-masked principal is bound unchanged, never downgraded")
    void preMaskedPrincipalNeverDowngraded() {
        RoleRegistry registry = new SingleMethodAdminRegistry();
        // ADVERSARIAL: principal already carries a precomputed mask (e.g. a trusted
        // gateway or resumed session). Enrichment must leave it untouched — same
        // instance, same mask — and never recompute/overwrite it with a smaller value.
        long preset = (1L << BIT_ADMIN) | (1L << 5);
        PrincipalContext preMasked = new FixedMaskPrincipal(preset);
        AtomicReference<PrincipalContext> bound = new AtomicReference<>();

        boolean invoked = runIntercept(registry, preMasked,
                () -> bound.set(KernelProviders.PRINCIPAL_CONTEXT.get()));

        assertThat(invoked).isTrue();
        assertThat(bound.get())
                .as("a pre-masked principal must be bound as the original instance")
                .isSameAs(preMasked);
        assertThat(bound.get().roleMask())
                .as("the pre-existing mask must be preserved bit-for-bit")
                .isEqualTo(preset);
    }

    @Test
    @DisplayName("Empty registry leaves the principal unwrapped with a 0L mask")
    void leavesPrincipalUntouchedWhenRegistryEmpty() {
        RoleRegistry empty = new EmptyRegistry();
        PrincipalContext principal = ImmutablePrincipal.system(UUID.randomUUID(), Set.of("ROLE_ADMIN"));
        AtomicReference<PrincipalContext> bound = new AtomicReference<>();

        boolean invoked = runIntercept(empty, principal,
                () -> bound.set(KernelProviders.PRINCIPAL_CONTEXT.get()));

        assertThat(invoked).isTrue();
        assertThat(bound.get())
                .as("empty registry must bind the original principal instance, not a wrapper")
                .isSameAs(principal);
        assertThat(bound.get().roleMask()).isZero();
    }

    /**
     * Principal carrying a precomputed {@code roleMask()} — the shape a trusted
     * gateway or resumed session presents. Used to assert the no-downgrade
     * invariant in {@link #preMaskedPrincipalNeverDowngraded()}.
     */
    private record FixedMaskPrincipal(long mask) implements PrincipalContext {
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
            return Set.of("ROLE_ADMIN");
        }

        @Override
        public Set<String> scopes() {
            return Set.of();
        }

        @Override
        public long roleMask() {
            return mask;
        }
    }

    /** Registry with a single ANY method requiring {@code ROLE_ADMIN}. */
    private static final class SingleMethodAdminRegistry implements RoleRegistry {
        @Override
        public long requiredAny(int methodId) {
            return 1L << BIT_ADMIN;
        }

        @Override
        public long requiredAll(int methodId) {
            return 0L;
        }

        @Override
        public boolean matchIsAll(int methodId) {
            return false;
        }

        @Override
        public int methodCount() {
            return 1;
        }

        @Override
        public int roleNameToBit(String roleName) {
            return "ROLE_ADMIN".equals(roleName) ? BIT_ADMIN : -1;
        }
    }

    /** Fail-closed empty registry mirroring the loader's absent-class default. */
    private static final class EmptyRegistry implements RoleRegistry {
        @Override
        public long requiredAny(int methodId) {
            return 0L;
        }

        @Override
        public long requiredAll(int methodId) {
            return 0L;
        }

        @Override
        public boolean matchIsAll(int methodId) {
            return false;
        }

        @Override
        public int methodCount() {
            return 0;
        }

        @Override
        public int roleNameToBit(String roleName) {
            return -1;
        }
    }
}
