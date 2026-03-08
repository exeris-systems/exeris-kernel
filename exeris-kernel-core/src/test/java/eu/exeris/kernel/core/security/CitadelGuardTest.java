/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.core.security;

import eu.exeris.kernel.spi.context.KernelProviders;
import eu.exeris.kernel.spi.exceptions.KernelErrorCodes;
import eu.exeris.kernel.spi.exceptions.security.InsufficientPrivilegesException;
import eu.exeris.kernel.spi.exceptions.security.PrincipalContextMissingException;
import eu.exeris.kernel.spi.security.ImmutablePrincipal;
import eu.exeris.kernel.spi.security.ImmutableStorageContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link CitadelGuard}.
 */
@DisplayName("CitadelGuard — unit tests")
class CitadelGuardTest {

    private static final UUID PRINCIPAL_ID = UUID.randomUUID();
    private static final UUID TENANT_ID    = UUID.randomUUID();

    private static final ImmutablePrincipal ADMIN_PRINCIPAL =
            ImmutablePrincipal.ofTenant(PRINCIPAL_ID, TENANT_ID, Set.of("ROLE_ADMIN", "ROLE_USER"));

    private static final ImmutablePrincipal USER_PRINCIPAL =
            ImmutablePrincipal.ofTenant(PRINCIPAL_ID, TENANT_ID, Set.of("ROLE_USER"));

    private CitadelGuard guard;

    @BeforeEach
    void setUp() {
        guard = new CitadelGuard();
    }

    // =========================================================================
    // preAllocate()
    // =========================================================================

    @Nested
    @DisplayName("preAllocate()")
    class PreAllocate {

        @Test
        @DisplayName("increments preAllocatedCount after first registration")
        void incrementsCount() {
            guard.preAllocate("ROLE_ADMIN");
            assertThat(guard.preAllocatedCount()).isEqualTo(1);
        }

        @Test
        @DisplayName("idempotent — second call for same role does not increase count")
        void idempotent() {
            guard.preAllocate("ROLE_ADMIN");
            guard.preAllocate("ROLE_ADMIN");
            assertThat(guard.preAllocatedCount()).isEqualTo(1);
        }

        @Test
        @DisplayName("multiple distinct roles each increment count")
        void distinctRoles() {
            guard.preAllocate("ROLE_ADMIN");
            guard.preAllocate("ROLE_USER");
            assertThat(guard.preAllocatedCount()).isEqualTo(2);
        }
    }

    // =========================================================================
    // requireRole() — happy path
    // =========================================================================

    @Nested
    @DisplayName("requireRole() — principal has required role")
    class RequireRoleHappyPath {

        @Test
        @DisplayName("does not throw when principal has the role")
        void doesNotThrowForValidRole() {
            ScopedValue.Carrier carrier = ScopedValue
                    .where(KernelProviders.PRINCIPAL_CONTEXT, ADMIN_PRINCIPAL)
                    .where(KernelProviders.STORAGE_CONTEXT, ImmutableStorageContext.GLOBAL);
            assertThatCode(() -> carrier.run(() -> guard.requireRole("ROLE_ADMIN")))
                    .doesNotThrowAnyException();
        }
    }

    // =========================================================================
    // requireRole() — rejection
    // =========================================================================

    @Nested
    @DisplayName("requireRole() — principal lacks required role")
    class RequireRoleRejection {

        @Test
        @DisplayName("throws InsufficientPrivilegesException for missing role")
        void throwsForMissingRole() {
            ScopedValue.Carrier carrier = ScopedValue
                    .where(KernelProviders.PRINCIPAL_CONTEXT, USER_PRINCIPAL)
                    .where(KernelProviders.STORAGE_CONTEXT, ImmutableStorageContext.GLOBAL);
            assertThatThrownBy(() -> carrier.run(() -> guard.requireRole("ROLE_ADMIN")))
                    .isInstanceOf(InsufficientPrivilegesException.class);
        }

        @Test
        @DisplayName("thrown exception carries EX-SEC-2003 error code")
        void thrownExceptionHasCorrectCode() {
            ScopedValue.Carrier carrier = ScopedValue
                    .where(KernelProviders.PRINCIPAL_CONTEXT, USER_PRINCIPAL)
                    .where(KernelProviders.STORAGE_CONTEXT, ImmutableStorageContext.GLOBAL);
            assertThatThrownBy(() -> carrier.run(() -> guard.requireRole("ROLE_ADMIN")))
                    .isInstanceOf(InsufficientPrivilegesException.class)
                    .satisfies(ex -> assertThat(((InsufficientPrivilegesException) ex).errorCode())
                            .isEqualTo(KernelErrorCodes.EX_SEC_2003));
        }

        @Test
        @DisplayName("Sentinel instance is thrown for pre-allocated roles (same object reference)")
        void sentinelIsThrownForPreAllocatedRole() {
            guard.preAllocate("ROLE_ADMIN");
            ScopedValue.Carrier carrier = ScopedValue
                    .where(KernelProviders.PRINCIPAL_CONTEXT, USER_PRINCIPAL)
                    .where(KernelProviders.STORAGE_CONTEXT, ImmutableStorageContext.GLOBAL);

            InsufficientPrivilegesException first  = catchRejection(carrier, "ROLE_ADMIN");
            InsufficientPrivilegesException second = catchRejection(carrier, "ROLE_ADMIN");

            assertThat(first).isNotNull();
            assertThat(second).isSameAs(first);
        }

        @Test
        @DisplayName("non-pre-allocated role throws a new instance each time")
        void nonPreAllocatedThrowsNewInstance() {
            ScopedValue.Carrier carrier = ScopedValue
                    .where(KernelProviders.PRINCIPAL_CONTEXT, USER_PRINCIPAL)
                    .where(KernelProviders.STORAGE_CONTEXT, ImmutableStorageContext.GLOBAL);

            InsufficientPrivilegesException first  = catchRejection(carrier, "ROLE_ADMIN");
            InsufficientPrivilegesException second = catchRejection(carrier, "ROLE_ADMIN");

            assertThat(first).isNotSameAs(second);
        }
    }

    // =========================================================================
    // hasRole()
    // =========================================================================

    @Nested
    @DisplayName("hasRole()")
    class HasRole {

        @Test
        @DisplayName("returns true when principal has role")
        void returnsTrueForPresentRole() {
            boolean[] result = new boolean[1];
            ScopedValue.where(KernelProviders.PRINCIPAL_CONTEXT, ADMIN_PRINCIPAL)
                    .where(KernelProviders.STORAGE_CONTEXT, ImmutableStorageContext.GLOBAL)
                    .run(() -> result[0] = guard.hasRole("ROLE_ADMIN"));
            assertThat(result[0]).isTrue();
        }

        @Test
        @DisplayName("returns false when principal lacks role")
        void returnsFalseForMissingRole() {
            boolean[] result = new boolean[1];
            ScopedValue.where(KernelProviders.PRINCIPAL_CONTEXT, USER_PRINCIPAL)
                    .where(KernelProviders.STORAGE_CONTEXT, ImmutableStorageContext.GLOBAL)
                    .run(() -> result[0] = guard.hasRole("ROLE_ADMIN"));
            assertThat(result[0]).isFalse();
        }
    }

    // =========================================================================
    // seal()
    // =========================================================================

    @Nested
    @DisplayName("seal()")
    class Seal {

        @Test
        @DisplayName("isSealed() returns false before seal() is called")
        void notSealedByDefault() {
            assertThat(guard.isSealed()).isFalse();
        }

        @Test
        @DisplayName("isSealed() returns true after seal() is called")
        void sealedAfterSeal() {
            guard.seal();
            assertThat(guard.isSealed()).isTrue();
        }

        @Test
        @DisplayName("seal() is idempotent — multiple calls do not throw")
        void sealIdempotent() {
            guard.seal();
            guard.seal();
            assertThat(guard.isSealed()).isTrue();
        }

        @Test
        @DisplayName("preAllocate() before seal() populates sentinel pool normally")
        void preAllocateBeforeSealSucceeds() {
            guard.preAllocate("ROLE_ADMIN");
            guard.seal();
            assertThat(guard.preAllocatedCount()).isEqualTo(1);
        }

        @Test
        @DisplayName("preAllocate() after seal() throws IllegalStateException")
        void preAllocateAfterSealThrows() {
            guard.preAllocate("ROLE_ADMIN");
            guard.seal();
            assertThatThrownBy(() -> guard.preAllocate("ROLE_NEW"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("sealed");
        }

        @Test
        @DisplayName("requireRole() works correctly on sealed guard — sentinel still thrown")
        void requireRoleWorksAfterSeal() {
            guard.preAllocate("ROLE_ADMIN");
            guard.seal();
            ScopedValue.Carrier carrier = ScopedValue
                    .where(KernelProviders.PRINCIPAL_CONTEXT, USER_PRINCIPAL)
                    .where(KernelProviders.STORAGE_CONTEXT, ImmutableStorageContext.GLOBAL);

            assertThat(catchRejection(carrier, "ROLE_ADMIN")).isNotNull();
        }
    }

    // =========================================================================
    // No scope — PrincipalContextMissingException
    // =========================================================================

    @Nested
    @DisplayName("Missing scope — PrincipalContextMissingException")
    class MissingScope {

        @Test
        @DisplayName("requireRole() throws PrincipalContextMissingException outside scope")
        void requireRoleThrowsOutsideScope() {
            assertThatThrownBy(() -> guard.requireRole("ROLE_ADMIN"))
                    .isInstanceOf(PrincipalContextMissingException.class);
        }

        @Test
        @DisplayName("hasRole() throws PrincipalContextMissingException outside scope")
        void hasRoleThrowsOutsideScope() {
            assertThatThrownBy(() -> guard.hasRole("ROLE_ADMIN"))
                    .isInstanceOf(PrincipalContextMissingException.class);
        }
    }

    // =========================================================================
    // helpers
    // =========================================================================

    private InsufficientPrivilegesException catchRejection(ScopedValue.Carrier carrier, String role) {
        try {
            carrier.run(() -> guard.requireRole(role));
            return null;
        } catch (InsufficientPrivilegesException ex) {
            return ex;
        }
    }
}
