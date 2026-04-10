/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.community.persistence;

import eu.exeris.kernel.community.security.CommunitySecurityProvider;
import eu.exeris.kernel.community.testkit.security.TestJwt;
import eu.exeris.kernel.spi.persistence.PersistenceConfig;
import eu.exeris.kernel.spi.persistence.PersistenceConnection;
import eu.exeris.kernel.spi.persistence.PersistenceEngine;
import eu.exeris.kernel.spi.security.AuthenticationResult;
import eu.exeris.kernel.spi.security.KernelIsolationClaims;
import eu.exeris.kernel.spi.security.StorageContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Integration test: end-to-end isolation strategy flow (Security edge → Persistence engine).
 *
 * <h2>Coverage</h2>
 * <ul>
 *   <li><b>SHARED</b>: no isolation claim in JWT → SHARED {@link StorageContext} → spy interceptor
 *       receives SHARED strategy (RlsConnectionInterceptor would fire {@code set_config})</li>
 *   <li><b>SEPARATED_SCHEMA</b>: claim present → SEPARATED_SCHEMA context, correct schemaName →
 *       spy interceptor receives SEPARATED_SCHEMA strategy (RlsConnectionInterceptor would fire
 *       {@code SET search_path})</li>
 *   <li><b>DEDICATED</b>: claim present → DEDICATED context, correct datasource key → engine routes
 *       to dedicated pool → spy interceptor receives DEDICATED strategy (RlsConnectionInterceptor
 *       would be a no-op)</li>
 * </ul>
 *
 * <h2>The Wall (boundary check)</h2>
 * <p>The Security subsystem resolves the {@link StorageContext} from JWT claims; the Persistence
 * engine consumes it without any knowledge of the original JWT payload. These are community
 * integration tests that bridge both subsystems — this cross-boundary import is intentional and
 * valid at this layer. The abstract TCK suites in {@code exeris-kernel-tck} must never import
 * Security provider or JWT types.
 *
 * @since 0.5.0
 */
@Tag("integration")
@DisplayName("Community: isolation strategy end-to-end flow (Security \u2192 Persistence)")
class CommunityIsolationStrategyEndToEndIT {

    private static final String DEDICATED_KEY = "ds-primary";

    private CommunitySecurityProvider security;
    private PersistenceEngine engine;

    @BeforeEach
    void setUp() {
        security = new CommunitySecurityProvider(
                TestJwt.keySet(), TestJwt.EXPECTED_ISSUER, TestJwt.EXPECTED_AUDIENCE);

        PersistenceConfig dedicatedCfg = new PersistenceConfig(
                "jdbc:h2:mem:e2e_dedicated_" + System.nanoTime() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
                "sa", "", 4, 1, 5_000L, 60_000L, 600_000L,
                false, false, false, 0, Map.of());

        PersistenceConfig mainCfg = new PersistenceConfig(
                "jdbc:h2:mem:e2e_main_" + System.nanoTime() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
                "sa", "", 8, 2, 5_000L, 60_000L, 600_000L,
                false, true, false, 4, Map.of(),
                Map.of(DEDICATED_KEY, dedicatedCfg));

        engine = new CommunityPersistenceProvider().createEngine(mainCfg);
    }

    @AfterEach
    void tearDown() {
        if (engine != null) {
            engine.close();
        }
    }

    // =========================================================================
    // SHARED — absent isolation claim
    // =========================================================================

    @Test
    @DisplayName("SHARED: absent isolation claim \u2192 SHARED StorageContext \u2192 interceptor sees SHARED strategy")
    void sharedIsolation_noClaimInJwt_interceptorSeesSharedStrategy() {
        // Security edge: absent isolation claim defaults to SHARED
        AuthenticationResult auth = security.authenticate(TestJwt.builder().toBuffer());
        StorageContext ctx = auth.storage();
        assertThat(ctx.strategy())
                .as("absent isolation claim must produce SHARED strategy")
                .isEqualTo(StorageContext.IsolationStrategy.SHARED);

        // Persistence: spy interceptor captures strategy
        AtomicReference<StorageContext.IsolationStrategy> captured = new AtomicReference<>();
        engine.registerInterceptor((conn, storageContext) -> captured.set(storageContext.strategy()));

        assertThatCode(() -> {
            try (PersistenceConnection conn = engine.openConnection(ctx)) {
                assertThat(conn.isOpen()).isTrue();
            }
        }).doesNotThrowAnyException();

        assertThat(captured.get())
                .as("Interceptor must receive SHARED strategy — RlsConnectionInterceptor "
                        + "would execute set_config('exeris.tenant_id', ...) for this strategy")
                .isEqualTo(StorageContext.IsolationStrategy.SHARED);
    }

    // =========================================================================
    // SEPARATED_SCHEMA — claim present
    // =========================================================================

    @Test
    @DisplayName("SEPARATED_SCHEMA: claim present \u2192 SEPARATED_SCHEMA context, correct schemaName \u2192 interceptor sees SEPARATED_SCHEMA")
    void separatedSchemaIsolation_claimPresent_interceptorSeesSeparatedSchemaStrategy() {
        // Security edge: SEPARATED_SCHEMA claim + schema name
        AuthenticationResult auth = security.authenticate(
                TestJwt.builder()
                        .claim(KernelIsolationClaims.ISOLATION_STRATEGY, "SEPARATED_SCHEMA")
                        .claim(KernelIsolationClaims.SCHEMA_NAME, "tenant_acme")
                        .toBuffer());
        StorageContext ctx = auth.storage();
        assertThat(ctx.strategy()).isEqualTo(StorageContext.IsolationStrategy.SEPARATED_SCHEMA);
        assertThat(ctx.schemaName()).isPresent().contains("tenant_acme");

        // Persistence: spy interceptor captures strategy
        AtomicReference<StorageContext.IsolationStrategy> captured = new AtomicReference<>();
        engine.registerInterceptor((conn, storageContext) -> captured.set(storageContext.strategy()));

        assertThatCode(() -> {
            try (PersistenceConnection conn = engine.openConnection(ctx)) {
                assertThat(conn.isOpen()).isTrue();
            }
        }).doesNotThrowAnyException();

        assertThat(captured.get())
                .as("Interceptor must receive SEPARATED_SCHEMA strategy — RlsConnectionInterceptor "
                        + "would execute SET search_path TO tenant_acme, public for this strategy")
                .isEqualTo(StorageContext.IsolationStrategy.SEPARATED_SCHEMA);
    }

    // =========================================================================
    // DEDICATED — claim present
    // =========================================================================

    @Test
    @DisplayName("DEDICATED: claim present \u2192 DEDICATED context \u2192 dedicated pool selected, interceptor sees DEDICATED (no RLS SQL)")
    void dedicatedIsolation_claimPresent_dedicatedPoolSelectedInterceptorSeesDedicatedStrategy() {
        // Security edge: DEDICATED claim + datasource key
        AuthenticationResult auth = security.authenticate(
                TestJwt.builder()
                        .claim(KernelIsolationClaims.ISOLATION_STRATEGY, "DEDICATED")
                        .claim(KernelIsolationClaims.DATASOURCE_KEY, DEDICATED_KEY)
                        .toBuffer());
        StorageContext ctx = auth.storage();
        assertThat(ctx.strategy()).isEqualTo(StorageContext.IsolationStrategy.DEDICATED);
        assertThat(ctx.dataSourceKey()).isPresent().contains(DEDICATED_KEY);

        // Persistence: spy interceptor captures strategy
        AtomicReference<StorageContext.IsolationStrategy> captured = new AtomicReference<>();
        engine.registerInterceptor((conn, storageContext) -> captured.set(storageContext.strategy()));

        assertThatCode(() -> {
            try (PersistenceConnection conn = engine.openConnection(ctx)) {
                assertThat(conn.isOpen()).isTrue();
            }
        }).as("DEDICATED connection must open successfully via the dedicated pool")
          .doesNotThrowAnyException();

        assertThat(captured.get())
                .as("Interceptor must receive DEDICATED strategy — RlsConnectionInterceptor "
                        + "is a no-op for DEDICATED (no SET exeris.tenant_id SQL emitted)")
                .isEqualTo(StorageContext.IsolationStrategy.DEDICATED);
    }
}
