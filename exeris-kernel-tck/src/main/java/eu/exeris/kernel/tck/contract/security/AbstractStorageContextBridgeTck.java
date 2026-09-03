/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.tck.contract.security;

import eu.exeris.kernel.spi.context.KernelProviders;
import eu.exeris.kernel.spi.exceptions.KernelErrorCodes;
import eu.exeris.kernel.spi.exceptions.security.PrincipalContextMissingException;
import eu.exeris.kernel.spi.security.ImmutablePrincipal;
import eu.exeris.kernel.spi.security.ImmutableStorageContext;
import eu.exeris.kernel.spi.security.PrincipalContext;
import eu.exeris.kernel.spi.security.StorageContext;
import eu.exeris.kernel.spi.security.StorageContext.IsolationStrategy;
import eu.exeris.kernel.tck.contract.JfrAllocationMonitor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * TCK: Abstract base for StorageContext bridge derivation verification.
 */
public abstract class AbstractStorageContextBridgeTck {

    protected abstract StorageContext derive(PrincipalContext principal);

    protected abstract StorageContext deriveFromActivePrincipal();

    protected UUID tenantId() {
        return UUID.fromString("00000000-0000-0000-0000-000000000042");
    }

    protected PrincipalContext createTenantPrincipal() {
        return ImmutablePrincipal.ofTenant(
                UUID.fromString("00000000-0000-0000-0000-000000000041"),
                tenantId(),
                Set.of("ROLE_USER"));
    }

    protected PrincipalContext createSystemPrincipal() {
        return ImmutablePrincipal.system(
                UUID.fromString("00000000-0000-0000-0000-000000000001"),
                Set.of("ROLE_SYSTEM"));
    }

    @Nested
    @DisplayName("derive(PrincipalContext) contract")
    class DirectDerivation {

        @Test
        @DisplayName("tenant principal derives SHARED context with tenant isolation key")
        void tenantPrincipalDerivesSharedContext() {
            StorageContext ctx = derive(createTenantPrincipal());

            assertThat(ctx.strategy()).isEqualTo(IsolationStrategy.SHARED);
            assertThat(ctx.isolationKey()).isPresent().contains(tenantId().toString());
            assertThat(ctx.schemaName()).isEmpty();
            assertThat(ctx.dataSourceKey()).isEmpty();
        }

        @Test
        @DisplayName("system principal maps to the GLOBAL singleton")
        void systemPrincipalMapsToGlobalSingleton() {
            StorageContext first = derive(createSystemPrincipal());
            StorageContext second = derive(createSystemPrincipal());

            assertThat(first).isSameAs(ImmutableStorageContext.GLOBAL);
            assertThat(second).isSameAs(first);
            assertThat(first.isolationKey()).isEmpty();
        }

        @Test
        @DisplayName("null principal is rejected with EX-SEC-2001")
        void nullPrincipalIsRejectedFailClosed() {
            assertThatThrownBy(() -> derive(null))
                    .isInstanceOf(PrincipalContextMissingException.class)
                    .satisfies(ex -> assertThat(((PrincipalContextMissingException) ex).errorCode())
                            .isEqualTo(KernelErrorCodes.EX_SEC_2001));
        }
    }

    @Nested
    @DisplayName("deriveFromActivePrincipal() scope contract")
    class ScopedDerivation {

        @Test
        @DisplayName("bound tenant principal is derived from ScopedValue context")
        void derivesFromBoundScope() {
            AtomicReference<StorageContext> result = new AtomicReference<>();

            ScopedValue.where(KernelProviders.PRINCIPAL_CONTEXT, createTenantPrincipal())
                    .where(KernelProviders.STORAGE_CONTEXT, ImmutableStorageContext.GLOBAL)
                    .run(() -> result.set(deriveFromActivePrincipal()));

            assertThat(result.get().strategy()).isEqualTo(IsolationStrategy.SHARED);
            assertThat(result.get().isolationKey()).contains(tenantId().toString());
        }

        @Test
        @DisplayName("missing ScopedValue binding throws EX-SEC-2001")
        void missingScopeThrowsCanonicalException() {
            assertThatThrownBy(AbstractStorageContextBridgeTck.this::deriveFromActivePrincipal)
                    .isInstanceOf(PrincipalContextMissingException.class)
                    .satisfies(ex -> assertThat(((PrincipalContextMissingException) ex).errorCode())
                            .isEqualTo(KernelErrorCodes.EX_SEC_2001));
        }
    }

    @Nested
    @DisplayName("JFR allocation contract")
    class AllocationContract {

        @Test
        @DisplayName("GLOBAL derivation path remains bounded under JFR-assisted telemetry")
        void globalDerivationIsAllocationDisciplined() throws IOException {
            PrincipalContext principal = createSystemPrincipal();
            JfrAllocationMonitor.Config config =
                    JfrAllocationMonitor.Config.ofDefaults("Security", getClass().getSimpleName());
            JfrAllocationMonitor.Result result = JfrAllocationMonitor.measure(config,
                    iterations -> {
                        for (int i = 0; i < iterations; i++) {
                            StorageContext ctx = derive(principal);
                            if (ctx.strategy() == null) {
                                throw new AssertionError("unreachable");
                            }
                        }
                    });

            JfrAllocationMonitor.assertBoundedExerisAllocations(
                    result,
                    config.hotPathIterations(),
                    1,
                    "StorageContextBridge GLOBAL path must stay allocation-disciplined under telemetry");
        }
    }
}
