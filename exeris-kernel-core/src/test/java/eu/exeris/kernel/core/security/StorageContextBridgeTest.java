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
import eu.exeris.kernel.spi.exceptions.security.PrincipalContextMissingException;
import eu.exeris.kernel.spi.security.ImmutablePrincipal;
import eu.exeris.kernel.spi.security.ImmutableStorageContext;
import eu.exeris.kernel.spi.security.StorageContext;
import eu.exeris.kernel.spi.security.StorageContext.IsolationStrategy;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link StorageContextBridge}.
 */
@DisplayName("StorageContextBridge — unit tests")
class StorageContextBridgeTest {

    private static final UUID PRINCIPAL_ID = UUID.randomUUID();
    private static final UUID TENANT_ID    = UUID.randomUUID();

    private static final ImmutablePrincipal TENANT_PRINCIPAL =
            ImmutablePrincipal.ofTenant(PRINCIPAL_ID, TENANT_ID, Set.of("ROLE_USER"));

    private static final ImmutablePrincipal SYSTEM_PRINCIPAL =
            ImmutablePrincipal.system(PRINCIPAL_ID, Set.of("ROLE_SYSTEM"));

    // =========================================================================
    // derive(PrincipalContext) — direct call
    // =========================================================================

    @Nested
    @DisplayName("derive(PrincipalContext) — direct call")
    class DeriveFromPrincipal {

        @Test
        @DisplayName("returns SHARED strategy context when principal has tenantId")
        void returnsSharedForTenantPrincipal() {
            StorageContext ctx = StorageContextBridge.derive(TENANT_PRINCIPAL);
            assertThat(ctx.strategy()).isEqualTo(IsolationStrategy.SHARED);
        }

        @Test
        @DisplayName("returned context carries the tenant UUID as isolation key")
        void isolationKeyMatchesTenantId() {
            StorageContext ctx = StorageContextBridge.derive(TENANT_PRINCIPAL);
            assertThat(ctx.isolationKey())
                    .isPresent()
                    .contains(TENANT_ID.toString());
        }

        @Test
        @DisplayName("returns the GLOBAL context when principal has no tenantId")
        void returnsGlobalForSystemPrincipal() {
            StorageContext ctx = StorageContextBridge.derive(SYSTEM_PRINCIPAL);
            // Equality, not identity: ImmutableStorageContext is a value class on this line.
            assertThat(ctx).isEqualTo(ImmutableStorageContext.GLOBAL);
        }

        @Test
        @DisplayName("GLOBAL singleton has empty isolation key")
        void globalHasEmptyIsolationKey() {
            StorageContext ctx = StorageContextBridge.derive(SYSTEM_PRINCIPAL);
            assertThat(ctx.isolationKey()).isEmpty();
        }

        @Test
        @DisplayName("null principal is rejected with PrincipalContextMissingException")
        void nullPrincipalRejectedFailClosed() {
            assertThatThrownBy(() -> StorageContextBridge.derive(null))
                    .isInstanceOf(PrincipalContextMissingException.class);
        }
    }

    // =========================================================================
    // deriveFromActivePrincipal() — reads from ScopedValue
    // =========================================================================

    @Nested
    @DisplayName("deriveFromActivePrincipal() — reads PRINCIPAL_CONTEXT from scope")
    class DeriveFromScope {

        @Test
        @DisplayName("returns SHARED context when bound principal has tenantId")
        void sharedContextFromScope() {
            AtomicReference<StorageContext> result = new AtomicReference<>();
            ScopedValue.where(KernelProviders.PRINCIPAL_CONTEXT, TENANT_PRINCIPAL)
                    .where(KernelProviders.STORAGE_CONTEXT, ImmutableStorageContext.GLOBAL)
                    .run(() -> result.set(StorageContextBridge.deriveFromActivePrincipal()));
            assertThat(result.get().strategy()).isEqualTo(IsolationStrategy.SHARED);
            assertThat(result.get().isolationKey()).isPresent().contains(TENANT_ID.toString());
        }

        @Test
        @DisplayName("returns GLOBAL singleton when bound principal has no tenantId")
        void globalContextFromScope() {
            AtomicReference<StorageContext> result = new AtomicReference<>();
            ScopedValue.where(KernelProviders.PRINCIPAL_CONTEXT, SYSTEM_PRINCIPAL)
                    .where(KernelProviders.STORAGE_CONTEXT, ImmutableStorageContext.GLOBAL)
                    .run(() -> result.set(StorageContextBridge.deriveFromActivePrincipal()));
            assertThat(result.get()).isEqualTo(ImmutableStorageContext.GLOBAL);
        }

        @Test
        @DisplayName("throws PrincipalContextMissingException when called outside scope")
        void throwsOutsideScope() {
            assertThatThrownBy(StorageContextBridge::deriveFromActivePrincipal)
                    .isInstanceOf(PrincipalContextMissingException.class);
        }
    }

    // =========================================================================
    // GLOBAL singleton — zero-alloc
    // =========================================================================

    @Nested
    @DisplayName("GLOBAL singleton — zero allocation contract")
    class GlobalSingleton {

        @Test
        @DisplayName("repeated derive() calls for system principal agree")
        void sameValueOnRepeatCalls() {
            StorageContext a = StorageContextBridge.derive(SYSTEM_PRINCIPAL);
            StorageContext b = StorageContextBridge.derive(SYSTEM_PRINCIPAL);
            // This used to assert instance reuse. With a value class that is neither observable
            // nor a contract: substitutability makes two equal contexts indistinguishable, and
            // whether either was allocated is left to the JVM.
            assertThat(a).isEqualTo(b);
        }
    }
}

