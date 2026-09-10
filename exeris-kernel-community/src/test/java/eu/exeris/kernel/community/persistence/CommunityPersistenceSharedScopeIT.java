/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.persistence;

import eu.exeris.kernel.spi.persistence.PersistenceConfig;
import eu.exeris.kernel.spi.persistence.PersistenceConnection;
import eu.exeris.kernel.spi.persistence.PersistenceEngine;
import eu.exeris.kernel.spi.persistence.PersistenceStatement;
import eu.exeris.kernel.spi.persistence.QueryResult;
import eu.exeris.kernel.spi.security.StorageContext;
import eu.exeris.kernel.tck.contract.persistence.AbstractSharedScopeAccessMatrixTck;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
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

/**
 * Community binding of {@link AbstractSharedScopeAccessMatrixTck} against a live PostgreSQL instance.
 *
 * <p>The abstract suite owns the matrix; this class owns only the substrate — a table, a conforming RLS
 * policy, and the two store operations. A live database is required because the contract is about RLS
 * behaviour, which the H2-backed {@code CommunityPersistenceEngineTckTest} cannot express.
 *
 * <h2>The conforming policy</h2>
 * <p>Reproduced here as the normative example. {@code USING} widens on the published shared scope;
 * {@code WITH CHECK} is byte-for-byte the tenant-private clause, because owner-scoped write is what that
 * clause already expresses — widening reads never requires relaxing writes. The {@code NULLIF} guard is
 * what stops a cleared scope from matching rows whose own tag is empty.
 *
 * <h2>Execution</h2>
 * <pre>mvn -pl exeris-kernel-community test -Dtest=CommunityPersistenceSharedScopeIT -DincludedGroups=integration -DexcludedGroups=</pre>
 *
 * @since 0.11.0
 */
@Tag("integration")
@Testcontainers(disabledWithoutDocker = true)
@DisplayName("Community persistence shared-scope access matrix — live PostgreSQL binding")
class CommunityPersistenceSharedScopeIT extends AbstractSharedScopeAccessMatrixTck {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16");

    private static final String APP_USER = "exeris_app";
    private static final String APP_PASSWORD = "exeris_app_pw";

    private static PersistenceEngine engine;

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

    /**
     * Seeds per test rather than once in {@code @BeforeAll}: the write-pin case deliberately attempts a
     * refused write, and re-seeding keeps each case independent of the others' side effects.
     */
    @org.junit.jupiter.api.BeforeEach
    void seedRows() {
        try (Connection conn = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             Statement st = conn.createStatement()) {
            st.execute("TRUNCATE scoped_docs");
        } catch (SQLException e) {
            throw new IllegalStateException("Truncate failed", e);
        }
        seedMatrixRows();
    }

    @Override
    protected void seed(StorageContext ctx, String owner, String sharedScope, String value) {
        try (PersistenceConnection conn = engine.openConnection(ctx);
             PersistenceStatement stmt = conn.prepare(
                     "INSERT INTO scoped_docs(tenant_id, shared_scope, value) VALUES (?, ?, ?)")) {
            stmt.bindString(0, owner)
                    .bindString(1, sharedScope == null ? "" : sharedScope)
                    .bindString(2, value)
                    .executeUpdate();
        }
    }

    @Override
    protected List<String> readVisible(StorageContext ctx) {
        List<String> rows = new ArrayList<>();
        try (PersistenceConnection conn = engine.openConnection(ctx);
             QueryResult result = conn.executeQuery("SELECT value FROM scoped_docs")) {
            while (result.next()) {
                rows.add(result.row().getString(0));
            }
        }
        return rows;
    }

    private static PersistenceEngine createEngine(PostgreSQLContainer<?> container) {
        PersistenceConfig config = new PersistenceConfig(
                container.getJdbcUrl(), APP_USER, APP_PASSWORD,
                24, 2, 5_000L, 60_000L, 600_000L,
                true, false, false, 0, Map.of());
        return new CommunityPersistenceProvider().createEngine(config);
    }

    private static void bootstrapSchema(PostgreSQLContainer<?> container) {
        try (Connection conn = DriverManager.getConnection(
                container.getJdbcUrl(), container.getUsername(), container.getPassword());
             Statement st = conn.createStatement()) {
            st.execute("DO $$ BEGIN "
                    + "  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'exeris_app') THEN "
                    + "    CREATE ROLE exeris_app LOGIN NOSUPERUSER PASSWORD 'exeris_app_pw'; "
                    + "  END IF; "
                    + "END $$");
            st.execute("DROP TABLE IF EXISTS scoped_docs CASCADE");
            st.execute("CREATE TABLE scoped_docs (tenant_id TEXT NOT NULL, "
                    + "shared_scope TEXT NOT NULL DEFAULT '', value TEXT NOT NULL)");
            st.execute("ALTER TABLE scoped_docs ENABLE ROW LEVEL SECURITY");
            st.execute("ALTER TABLE scoped_docs FORCE ROW LEVEL SECURITY");
            st.execute("DROP POLICY IF EXISTS shared_scope_isolation ON scoped_docs");
            st.execute("CREATE POLICY shared_scope_isolation ON scoped_docs "
                    + "USING (tenant_id = current_setting('exeris.tenant_id', true) "
                    + "       OR (NULLIF(current_setting('exeris.shared_scope', true), '') IS NOT NULL "
                    + "           AND shared_scope = current_setting('exeris.shared_scope', true))) "
                    + "WITH CHECK (tenant_id = current_setting('exeris.tenant_id', true))");
            st.execute("GRANT USAGE ON SCHEMA public TO exeris_app");
            st.execute("GRANT SELECT, INSERT, UPDATE, DELETE ON scoped_docs TO exeris_app");
        } catch (SQLException e) {
            throw new IllegalStateException("Bootstrap schema failed", e);
        }
    }
}
