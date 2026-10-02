/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.persistence;

import eu.exeris.kernel.spi.context.KernelProviders;
import eu.exeris.kernel.spi.persistence.PersistenceConfig;
import eu.exeris.kernel.spi.persistence.PersistenceConnection;
import eu.exeris.kernel.spi.persistence.PersistenceEngine;
import eu.exeris.kernel.spi.persistence.PersistenceStatement;
import eu.exeris.kernel.spi.persistence.QueryResult;
import eu.exeris.kernel.spi.security.ImmutableStorageContext;
import eu.exeris.kernel.spi.security.StorageContext;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.StructuredTaskScope;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * WS5 Integration: SHARED RLS tenant isolation via {@link RlsConnectionInterceptor} against a live
 * PostgreSQL instance provisioned by Testcontainers.
 *
 * <h2>What this proves</h2>
 * <ul>
 *   <li>RLS policy {@code tenant_isolation} correctly restricts row visibility per tenant.</li>
 *   <li>128 concurrent virtual threads alternating between two tenants produce no cross-tenant leaks.</li>
 *   <li>All connections are returned to the pool after a structured scope exits — zero active
 *       connections remain.</li>
 * </ul>
 *
 * <h2>Execution</h2>
 * <p>Tagged {@code integration} and skipped automatically when Docker is unavailable.
 * Run explicitly with:
 * <pre>mvn -pl exeris-kernel-community test -Dtest=CommunityPersistenceTenantIsolationIT -DincludedGroups=integration -DexcludedGroups=</pre>
 *
 * @since 0.5.0
 */
@Tag("integration")
@Testcontainers(disabledWithoutDocker = true)
@DisplayName("WS5: Community persistence SHARED RLS tenant isolation — Testcontainers integration")
class CommunityPersistenceTenantIsolationIT {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16");

    private static PersistenceEngine engine;

    private static final String APP_USER     = "exeris_app";
    private static final String APP_PASSWORD = "exeris_app_pw";

    private static final String TENANT_A = "tenant-a";
    private static final String TENANT_B = "tenant-b";
    private static final ImmutableStorageContext CTX_A = ImmutableStorageContext.shared(TENANT_A);
    private static final ImmutableStorageContext CTX_B = ImmutableStorageContext.shared(TENANT_B);

    @BeforeAll
    static void startEngine() {
        bootstrapSchema(POSTGRES);
        engine = createEngine(POSTGRES);
        engine.registerInterceptor(RlsConnectionInterceptor.INSTANCE);
    }

    @AfterAll
    static void stopEngine() {
        if (engine != null) {
            engine.close();
        }
    }

    // =========================================================================
    // Tests
    // =========================================================================

    @Test
    @DisplayName("tenant-a rows are invisible to tenant-b and vice versa")
    void tenantA_cannotSeeTenantB_andViceVersa() {
        insertTenantRow(engine, CTX_A, "seed-a");
        insertTenantRow(engine, CTX_B, "seed-b");

        List<String> visibleToA = readVisibleRows(engine, CTX_A);
        List<String> visibleToB = readVisibleRows(engine, CTX_B);

        assertThat(visibleToA).contains("seed-a").doesNotContain("seed-b");
        assertThat(visibleToB).contains("seed-b").doesNotContain("seed-a");
    }

    @Test
    @DisplayName("128 concurrent VTs alternating tenants have no RLS leaks and no init-failure storm")
    void vtStress_sharedContext_hasNoLeaksAndNoInitFailureStorm() throws Exception {
        int taskCount = 128;

        try (var scope = StructuredTaskScope.open(
                StructuredTaskScope.Joiner.<Void>awaitAllSuccessfulOrThrow())) {
            for (int i = 0; i < taskCount; i++) {
                final int idx = i;
                final boolean isA = (idx % 2 == 0);
                final ImmutableStorageContext ctx = isA ? CTX_A : CTX_B;
                final String expectedTenant = isA ? TENANT_A : TENANT_B;
                final String rowValue = isA ? "A-" + idx : "B-" + idx;

                scope.fork(() -> {
                    ScopedValue.where(KernelProviders.STORAGE_CONTEXT, ctx).run(() -> {
                        try (PersistenceConnection conn = engine.openConnection(ctx)) {
                            try (QueryResult result = conn.executeQuery(
                                    "SELECT current_setting('exeris.tenant_id', true)")) {
                                assertThat(result.next()).isTrue();
                                assertThat(result.row().getString(0)).isEqualTo(expectedTenant);
                            }
                            try (PersistenceStatement insert = conn.prepare(
                                    "INSERT INTO tenant_docs(tenant_id, value) VALUES (?, ?)")) {
                                insert.bindString(0, expectedTenant)
                                      .bindString(1, rowValue)
                                      .executeUpdate();
                            }
                        }
                    });
                    return null;
                });
            }
            scope.join();
        }

        assertThat(engine.stats().activeConnections())
                .as("all connections must be returned to pool after scope exits")
                .isZero();

        List<String> finalA = readVisibleRows(engine, CTX_A);
        List<String> finalB = readVisibleRows(engine, CTX_B);

        assertThat(finalA).isNotEmpty().allSatisfy(v -> assertThat(v).matches("A-\\d+|seed-a"));
        assertThat(finalB).isNotEmpty().allSatisfy(v -> assertThat(v).matches("B-\\d+|seed-b"));
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private static PersistenceEngine createEngine(PostgreSQLContainer<?> container) {
        PersistenceConfig config = new PersistenceConfig(
                container.getJdbcUrl(),
                APP_USER,
                APP_PASSWORD,
                24,
                2,
                5_000L,
                60_000L,
                600_000L,
                true,
                false,
                false,
                0,
                Map.of()
        );
        return new CommunityPersistenceProvider().createEngine(config);
    }

    private static void bootstrapSchema(PostgreSQLContainer<?> container) {
        try (Connection conn = DriverManager.getConnection(
                     container.getJdbcUrl(), container.getUsername(), container.getPassword());
             Statement st = conn.createStatement()) {
            st.execute("DO $$ BEGIN " +
                       "  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'exeris_app') THEN " +
                       "    CREATE ROLE exeris_app LOGIN NOSUPERUSER PASSWORD 'exeris_app_pw'; " +
                       "  END IF; " +
                       "END $$");
            st.execute("DROP TABLE IF EXISTS tenant_docs CASCADE");
            st.execute("CREATE TABLE tenant_docs (tenant_id TEXT NOT NULL, value TEXT NOT NULL)");
            st.execute("ALTER TABLE tenant_docs ENABLE ROW LEVEL SECURITY");
            st.execute("ALTER TABLE tenant_docs FORCE ROW LEVEL SECURITY");
            st.execute("DROP POLICY IF EXISTS tenant_isolation ON tenant_docs");
            st.execute("CREATE POLICY tenant_isolation ON tenant_docs " +
                       "USING (tenant_id = current_setting('exeris.tenant_id', true)) " +
                       "WITH CHECK (tenant_id = current_setting('exeris.tenant_id', true))");
            st.execute("GRANT USAGE ON SCHEMA public TO exeris_app");
            st.execute("GRANT SELECT, INSERT, UPDATE, DELETE ON tenant_docs TO exeris_app");
        } catch (SQLException e) {
            throw new RuntimeException("Bootstrap schema failed", e);
        }
    }

    private static void insertTenantRow(PersistenceEngine e, StorageContext ctx, String value) {
        try (PersistenceConnection conn = e.openConnection(ctx);
             PersistenceStatement stmt = conn.prepare(
                     "INSERT INTO tenant_docs(tenant_id, value) VALUES (?, ?)")) {
            stmt.bindString(0, ctx.isolationKey().orElseThrow())
                .bindString(1, value)
                .executeUpdate();
        }
    }

    private static List<String> readVisibleRows(PersistenceEngine e, StorageContext ctx) {
        List<String> rows = new ArrayList<>();
        try (PersistenceConnection conn = e.openConnection(ctx);
             QueryResult result = conn.executeQuery("SELECT value FROM tenant_docs")) {
            while (result.next()) {
                rows.add(result.row().getString(0));
            }
        }
        return rows;
    }
}
