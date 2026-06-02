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
