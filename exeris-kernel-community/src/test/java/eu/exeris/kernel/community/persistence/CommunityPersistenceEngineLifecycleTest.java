/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.community.persistence;

import eu.exeris.kernel.spi.exceptions.KernelErrorCodes;
import eu.exeris.kernel.spi.exceptions.persistence.PersistenceProviderException;
import eu.exeris.kernel.spi.persistence.PersistenceConfig;
import eu.exeris.kernel.spi.persistence.PersistenceConnection;
import eu.exeris.kernel.spi.persistence.QueryResult;
import eu.exeris.kernel.spi.security.ImmutableStorageContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("CommunityPersistenceEngine lifecycle hardening")
class CommunityPersistenceEngineLifecycleTest {

    @Test
    @DisplayName("DEDICATED strategy is explicitly rejected in Community provider")
    void dedicatedStrategyIsRejected() {
        try (CommunityPersistenceEngine engine = new CommunityPersistenceEngine(testConfig(true))) {
            assertThatThrownBy(() -> engine.openConnection(ImmutableStorageContext.dedicated("tenant-a", "ds-a")))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("DEDICATED strategy is unsupported in Community provider")
                    .hasMessageContaining("dedicated datasource routing is unsupported in Community provider");
        }
    }

    @Test
    @DisplayName("close() is idempotent and blocks subsequent opens")
    void closeIsIdempotentAndPreventsFurtherOpen() {
        CommunityPersistenceEngine engine = new CommunityPersistenceEngine(testConfig(true));
        engine.close();

        assertThatCode(engine::close).doesNotThrowAnyException();
        assertThatThrownBy(engine::openConnection)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("CommunityPersistenceEngine is closed");
        assertThatThrownBy(() -> engine.openConnection(ImmutableStorageContext.separatedSchema("tenant-a", "tenant_a")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("CommunityPersistenceEngine is closed");
    }

    @Test
    @DisplayName("open/close race allows only success-before-close or closed-state rejection")
    void openCloseRaceIsDeterministicAtContractLevel() throws Exception {
        for (int i = 0; i < 40; i++) {
            int iteration = i;
            CommunityPersistenceEngine engine = new CommunityPersistenceEngine(testConfig(true));
            CountDownLatch start = new CountDownLatch(1);
            AtomicReference<Throwable> unexpected = new AtomicReference<>();

            Thread opener = Thread.ofVirtual().start(() -> {
                await(start);
                try (PersistenceConnection ignored =
                             engine.openConnection(ImmutableStorageContext.separatedSchema(
                                     "tenant-" + iteration,
                                     "tenant_" + iteration))) {
                    // success path: open won the race before close transition
                } catch (IllegalStateException ex) {
                    assertThat(ex.getMessage()).contains("CommunityPersistenceEngine is closed");
                } catch (Throwable ex) {
                    unexpected.compareAndSet(null, ex);
                }
            });

            Thread closer = Thread.ofVirtual().start(() -> {
                await(start);
                engine.close();
            });

            start.countDown();
            opener.join();
            closer.join();
            assertThat(unexpected.get()).isNull();
            engine.close();
        }
    }

    @Test
    @DisplayName("shared and tenant pools do not share an extra checkout gate")
    void crossPoolCheckoutReliesOnPoolLimitsOnly() {
        PersistenceConfig config = testConfig(true, 2, 1, 300L, 80L, 16);
        try (CommunityPersistenceEngine engine = new CommunityPersistenceEngine(config);
             PersistenceConnection shared = engine.openConnection();
             PersistenceConnection tenantA = engine.openConnection(
                     ImmutableStorageContext.separatedSchema("tenant-a", "tenant_a"))) {

            assertThatCode(() -> engine.openConnection(
                    ImmutableStorageContext.separatedSchema("tenant-b", "tenant_b")).close())
                    .doesNotThrowAnyException();
        }
    }

    @Test
    @DisplayName("EX_PERS_5002 diagnostics use active shared-pool connection count")
    void connectionExhaustedDiagnosticsUsePoolActiveConnections() {
        PersistenceConfig config = testConfig(false, 2, 1, 300L, 80L, 16);
        try (CommunityPersistenceEngine engine = new CommunityPersistenceEngine(config);
             PersistenceConnection first = engine.openConnection();
             PersistenceConnection second = engine.openConnection()) {

            assertThatThrownBy(engine::openConnection)
                    .isInstanceOf(PersistenceProviderException.class)
                    .satisfies(ex -> {
                        PersistenceProviderException ppe = (PersistenceProviderException) ex;
                        assertThat(ppe.errorCode()).isEqualTo(KernelErrorCodes.EX_PERS_5002);
                        assertThat(ppe.rawArgs()[0]).isEqualTo("postgres-community");
                        assertThat(((Long) ppe.rawArgs()[1])).isEqualTo(300L);
                        assertThat(((Integer) ppe.rawArgs()[2])).isGreaterThanOrEqualTo(2);
                    });
        }
    }

    @Test
    @DisplayName("EX_PERS_5002 rawArgs[2] reflects active-connection count, not tenant-pool count")
    void maxTenantPoolsExhaustedDiagnosticsUseActiveConnectionCount() {
        PersistenceConfig config = testConfig(true, 4, 0, 300L, 80L, 1);
        try (CommunityPersistenceEngine engine = new CommunityPersistenceEngine(config)) {
            // Open and immediately close a connection for tenant-a: pool created, active=0 after close.
            try (PersistenceConnection ignored =
                         engine.openConnection(ImmutableStorageContext.separatedSchema("tenant-a", "tenant_a"))) {
                // connection held during this block; close at end of try
            }
            // Pool for tenant-a exists but no active connections. Attempting tenant-b must hit the cap.
            assertThatThrownBy(() -> engine.openConnection(
                            ImmutableStorageContext.separatedSchema("tenant-b", "tenant_b")))
                    .isInstanceOf(PersistenceProviderException.class)
                    .satisfies(ex -> {
                        PersistenceProviderException ppe = (PersistenceProviderException) ex;
                        assertThat(ppe.errorCode()).isEqualTo(KernelErrorCodes.EX_PERS_5002);
                        assertThat(ppe.rawArgs()[0]).isEqualTo("postgres-community");
                        assertThat(((Long) ppe.rawArgs()[1])).isEqualTo(300L);
                        assertThat(((Integer) ppe.rawArgs()[2])).isEqualTo(0);
                    });
        }
    }

    @Test
    @DisplayName("permit is released when interceptor initialization fails")
    void permitReleasedOnInterceptorFailure() {
        PersistenceConfig config = testConfig(true, 1, 0, 300L, 80L, 16);
        try (CommunityPersistenceEngine engine = new CommunityPersistenceEngine(config)) {
            engine.registerInterceptor((connection, storageContext) -> {
                throw new IllegalStateException("boom");
            });

            assertThatThrownBy(() -> engine.openConnection(
                    ImmutableStorageContext.separatedSchema("tenant-a", "tenant_a")))
                    .isInstanceOf(PersistenceProviderException.class)
                    .satisfies(ex -> {
                        PersistenceProviderException ppe = (PersistenceProviderException) ex;
                        assertThat(ppe.errorCode()).isEqualTo(KernelErrorCodes.EX_PERS_5006);
                    });

            assertThatThrownBy(() -> engine.openConnection(
                    ImmutableStorageContext.separatedSchema("tenant-b", "tenant_b")))
                    .isInstanceOf(PersistenceProviderException.class)
                    .satisfies(ex -> {
                        PersistenceProviderException ppe = (PersistenceProviderException) ex;
                        assertThat(ppe.errorCode()).isEqualTo(KernelErrorCodes.EX_PERS_5006);
                    });
        }
    }

    @Test
    @DisplayName("idle tenant pools are reclaimed only when inactive")
    void tenantPoolReclaimHonorsActiveConnections() throws Exception {
        PersistenceConfig config = testConfig(true, 4, 0, 500L, 120L, 16);
        try (CommunityPersistenceEngine engine = new CommunityPersistenceEngine(config);
             PersistenceConnection activeTenant = engine.openConnection(
                     ImmutableStorageContext.separatedSchema("tenant-a", "tenant_a"))) {

            Thread.sleep(320L);
            try (PersistenceConnection ignored = engine.openConnection(
                    ImmutableStorageContext.separatedSchema("tenant-b", "tenant_b"))) {
                assertThat(engine.stats().tenantPoolCount()).isEqualTo(2);
            }

            activeTenant.close();
            Thread.sleep(320L);
            try (PersistenceConnection ignored = engine.openConnection(
                    ImmutableStorageContext.separatedSchema("tenant-c", "tenant_c"))) {
                assertThat(engine.stats().tenantPoolCount()).isLessThanOrEqualTo(2);
            }
        }
    }

    @Test
    @DisplayName("EX_PERS_5006 emitted when RLS enabled and SHARED interceptor is absent")
    void sharedOpenFailsFastWhenRlsEnabledAndInterceptorMissing() {
        PersistenceConfig config = testConfig(false, true);
        try (CommunityPersistenceEngine engine = new CommunityPersistenceEngine(config)) {
            assertThatThrownBy(() -> engine.openConnection(ImmutableStorageContext.shared("tenant-a")))
                    .isInstanceOf(PersistenceProviderException.class)
                    .satisfies(ex -> {
                        PersistenceProviderException ppe = (PersistenceProviderException) ex;
                        assertThat(ppe.errorCode()).isEqualTo(KernelErrorCodes.EX_PERS_5006);
                        assertThat(ppe.rawArgs()[0]).isEqualTo("RlsConnectionInterceptor");
                        assertThat(ppe.rawArgs()[1]).isEqualTo("tenant-a");
                    });
        }
    }

    @Test
    @DisplayName("pool remains healthy and reusable after interceptor failure (discard path)")
    void poolHealthyAfterInterceptorFailure() throws Exception {
        PersistenceConfig config = testConfig(true, 2, 1, 5_000L, 60_000L, 16);
        try (CommunityPersistenceEngine engine = new CommunityPersistenceEngine(config)) {
            AtomicBoolean failOnce = new AtomicBoolean(true);
            engine.registerInterceptor((connection, storageContext) -> {
                if (failOnce.compareAndSet(true, false)) {
                    throw new IllegalStateException("injected interceptor failure");
                }
            });

            assertThatThrownBy(() -> engine.openConnection(
                    ImmutableStorageContext.separatedSchema("tenant-a", "tenant_a")))
                    .isInstanceOf(PersistenceProviderException.class)
                    .satisfies(ex -> {
                        PersistenceProviderException ppe = (PersistenceProviderException) ex;
                        assertThat(ppe.errorCode()).isEqualTo(KernelErrorCodes.EX_PERS_5006);
                    });

            assertThat(engine.stats().activeConnections()).isZero();

            try (PersistenceConnection conn = engine.openConnection(
                    ImmutableStorageContext.separatedSchema("tenant-a", "tenant_a"));
                 QueryResult result = conn.executeQuery("SELECT 1")) {
                assertThat(result.next()).isTrue();
                assertThat(result.row().getInt(0)).isEqualTo(1);
            }
        }
    }

    @Test
    @DisplayName("perTenantPooling=true with maxTenantPools=0 fails fast")
    void invalidPerTenantConfigFailsFast() {
        PersistenceConfig config = testConfig(true, 4, 1, 500L, 120L, 0);
        assertThatThrownBy(() -> new CommunityPersistenceEngine(config))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("perTenantPooling=true requires maxTenantPools>=1")
                .hasMessageContaining("maxTenantPools=0")
                .hasMessageContaining("maxPoolSize=4");
    }

    @Test
    @DisplayName("connectionTimeoutMs below Hikari floor fails fast")
    void invalidConnectionTimeoutFailsFast() {
        PersistenceConfig config = testConfig(true, 4, 1, 200L, 120L, 16);
        assertThatThrownBy(() -> new CommunityPersistenceEngine(config))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("connectionTimeoutMs must be >= 250ms")
                .hasMessageContaining("connectionTimeoutMs=200")
                .hasMessageContaining("maxPoolSize=4");
    }

    private static PersistenceConfig testConfig(boolean perTenantPooling) {
        return testConfig(perTenantPooling, false);
    }

    private static PersistenceConfig testConfig(boolean perTenantPooling, boolean rlsEnabled) {
        return new PersistenceConfig(
                "jdbc:h2:mem:community_lifecycle_" + System.nanoTime() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
                "sa",
                "",
                4,
                1,
                5_000L,
                60_000L,
                600_000L,
                rlsEnabled,
                perTenantPooling,
                false,
                16,
                Map.of()
        );
    }

    private static PersistenceConfig testConfig(boolean perTenantPooling,
                                                int maxPoolSize,
                                                int minIdleConnections,
                                                long connectionTimeoutMs,
                                                long idleTimeoutMs,
                                                int maxTenantPools) {
        return new PersistenceConfig(
                "jdbc:h2:mem:community_lifecycle_" + System.nanoTime() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
                "sa",
                "",
                maxPoolSize,
                minIdleConnections,
                connectionTimeoutMs,
                idleTimeoutMs,
                600_000L,
                false,
                perTenantPooling,
                false,
                maxTenantPools,
                Map.of()
        );
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while awaiting test latch", ex);
        }
    }
}
