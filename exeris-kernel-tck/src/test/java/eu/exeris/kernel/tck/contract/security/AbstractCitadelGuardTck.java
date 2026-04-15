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
import eu.exeris.kernel.spi.exceptions.KernelErrorCodes;
import eu.exeris.kernel.spi.exceptions.security.InsufficientPrivilegesException;
import eu.exeris.kernel.spi.exceptions.security.PrincipalContextMissingException;
import eu.exeris.kernel.spi.security.ImmutablePrincipal;
import eu.exeris.kernel.spi.security.ImmutableStorageContext;
import eu.exeris.kernel.spi.security.PrincipalContext;
import eu.exeris.kernel.tck.contract.JfrAllocationMonitor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * TCK: Abstract base for Citadel-style RBAC gate verification.
 *
 * @param <G> concrete guard type under test
 */
public abstract class AbstractCitadelGuardTck<G> {

    protected abstract G createGuard();

    protected abstract void preAllocate(G guard, String requiredRole);

    protected abstract void seal(G guard);

    protected abstract boolean isSealed(G guard);

    protected abstract void requireRole(G guard, String requiredRole);

    protected abstract boolean hasRole(G guard, String role);

    protected abstract int preAllocatedCount(G guard);

    protected PrincipalContext createAdminPrincipal() {
        return ImmutablePrincipal.ofTenant(
                UUID.randomUUID(), UUID.randomUUID(), Set.of("ROLE_ADMIN", "ROLE_USER"));
    }

    protected PrincipalContext createUserPrincipal() {
        return ImmutablePrincipal.ofTenant(
                UUID.randomUUID(), UUID.randomUUID(), Set.of("ROLE_USER"));
    }

    private G guard;

    @BeforeEach
    final void setUpGuard() {
        guard = createGuard();
    }

    @Nested
    @DisplayName("preAllocate() and seal() contract")
    class BootstrapContract {

        @Test
        @DisplayName("first preAllocate() call increments the sentinel pool size")
        void preAllocateIncrementsCount() {
            preAllocate(guard, "ROLE_ADMIN");
            assertThat(preAllocatedCount(guard)).isEqualTo(1);
        }

        @Test
        @DisplayName("preAllocate() is idempotent for the same role")
        void preAllocateIsIdempotent() {
            preAllocate(guard, "ROLE_ADMIN");
            preAllocate(guard, "ROLE_ADMIN");
            assertThat(preAllocatedCount(guard)).isEqualTo(1);
        }

        @Test
        @DisplayName("seal() prevents further sentinel registrations")
        void sealPreventsFurtherRegistration() {
            preAllocate(guard, "ROLE_ADMIN");
            seal(guard);

            assertThat(isSealed(guard)).isTrue();
            assertThatThrownBy(() -> preAllocate(guard, "ROLE_AUDITOR"))
                    .isInstanceOf(IllegalStateException.class);
        }
    }

    @Nested
    @DisplayName("requireRole() contract")
    class RequireRoleContract {

        @Test
        @DisplayName("principal with the required role is allowed through")
        void allowsPrincipalWithRequiredRole() {
            ScopedValue.where(KernelProviders.PRINCIPAL_CONTEXT, createAdminPrincipal())
                    .where(KernelProviders.STORAGE_CONTEXT, ImmutableStorageContext.GLOBAL)
                    .run(() -> assertThatCode(() -> requireRole(guard, "ROLE_ADMIN"))
                            .doesNotThrowAnyException());
        }

        @Test
        @DisplayName("hasRole() mirrors the bound principal roles")
        void hasRoleReflectsPrincipal() {
            boolean[] result = new boolean[2];
            ScopedValue.where(KernelProviders.PRINCIPAL_CONTEXT, createAdminPrincipal())
                    .where(KernelProviders.STORAGE_CONTEXT, ImmutableStorageContext.GLOBAL)
                    .run(() -> {
                        result[0] = hasRole(guard, "ROLE_ADMIN");
                        result[1] = hasRole(guard, "ROLE_BILLING");
                    });

            assertThat(result[0]).isTrue();
            assertThat(result[1]).isFalse();
        }

        @Test
        @DisplayName("missing role is rejected with EX-SEC-2003")
        void rejectsMissingRoleWithCanonicalErrorCode() {
            ScopedValue.where(KernelProviders.PRINCIPAL_CONTEXT, createUserPrincipal())
                    .where(KernelProviders.STORAGE_CONTEXT, ImmutableStorageContext.GLOBAL)
                    .run(() -> assertThatThrownBy(() -> requireRole(guard, "ROLE_ADMIN"))
                            .isInstanceOf(InsufficientPrivilegesException.class)
                            .satisfies(ex -> assertThat(((InsufficientPrivilegesException) ex).errorCode())
                                    .isEqualTo(KernelErrorCodes.EX_SEC_2003)));
        }

        @Test
        @DisplayName("pre-allocated role rejection reuses the same sentinel instance")
        void reusesSentinelForPreAllocatedRole() {
            preAllocate(guard, "ROLE_ADMIN");
            seal(guard);

            ScopedValue.Carrier carrier = ScopedValue
                    .where(KernelProviders.PRINCIPAL_CONTEXT, createUserPrincipal())
                    .where(KernelProviders.STORAGE_CONTEXT, ImmutableStorageContext.GLOBAL);

            InsufficientPrivilegesException first = catchRejection(carrier, "ROLE_ADMIN");
            InsufficientPrivilegesException second = catchRejection(carrier, "ROLE_ADMIN");

            assertThat(first).isSameAs(second);
        }

        @Test
        @DisplayName("calling requireRole() outside a security scope is fail-closed")
        void requiresBoundPrincipalContext() {
            assertThatThrownBy(() -> requireRole(guard, "ROLE_ADMIN"))
                    .isInstanceOf(PrincipalContextMissingException.class);
        }
    }

    @Nested
    @DisplayName("JFR allocation contract")
    class AllocationContract {

        @Test
        @DisplayName("steady-state allow path performs zero eu.exeris allocations")
        void allowPathIsAllocationFree() throws IOException {
            PrincipalContext principal = createAdminPrincipal();
            JfrAllocationMonitor.Result result = JfrAllocationMonitor.measure(
                    JfrAllocationMonitor.Config.ofDefaults("Security", getClass().getSimpleName()),
                    iterations -> ScopedValue.where(KernelProviders.PRINCIPAL_CONTEXT, principal)
                            .where(KernelProviders.STORAGE_CONTEXT, ImmutableStorageContext.GLOBAL)
                            .run(() -> {
                                for (int i = 0; i < iterations; i++) {
                                    requireRole(guard, "ROLE_ADMIN");
                                }
                            }));

            JfrAllocationMonitor.assertZeroExerisAllocations(result,
                    "CitadelGuard allow path must not allocate eu.exeris.* objects");
        }
    }

    private InsufficientPrivilegesException catchRejection(ScopedValue.Carrier carrier, String requiredRole) {
        try {
            carrier.run(() -> requireRole(guard, requiredRole));
            return null;
        } catch (InsufficientPrivilegesException ex) {
            return ex;
        }
    }
}
