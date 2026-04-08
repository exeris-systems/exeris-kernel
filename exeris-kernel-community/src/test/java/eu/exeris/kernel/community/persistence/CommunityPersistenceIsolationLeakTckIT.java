/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.community.persistence;

import eu.exeris.kernel.spi.persistence.PersistenceConfig;
import eu.exeris.kernel.spi.persistence.PersistenceConnection;
import eu.exeris.kernel.spi.persistence.PersistenceEngine;
import eu.exeris.kernel.spi.persistence.PersistenceStatement;
import eu.exeris.kernel.spi.persistence.QueryResult;
import eu.exeris.kernel.tck.contract.persistence.PersistenceIsolationLeakTck;
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

@Tag("integration")
@Testcontainers(disabledWithoutDocker = true)
@DisplayName("Community: Persistence isolation leak TCK (PostgreSQL + RLS)")
class CommunityPersistenceIsolationLeakTckIT extends PersistenceIsolationLeakTck {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16");

    private static final String APP_USER     = "exeris_app";
    private static final String APP_PASSWORD = "exeris_app_pw";

    @Override
    protected PersistenceEngine createEngine() {
        bootstrapSchema(POSTGRES);
        PersistenceConfig config = new PersistenceConfig(
                POSTGRES.getJdbcUrl(),
                APP_USER,
                APP_PASSWORD,
                16,
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
        PersistenceEngine engine = new CommunityPersistenceProvider().createEngine(config);
        engine.registerInterceptor(RlsConnectionInterceptor.INSTANCE);
        return engine;
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

    @Override
    protected void writeSentinelRow(PersistenceConnection conn, String value) {
        try (PersistenceStatement stmt = conn.prepare(
                "INSERT INTO tenant_docs(tenant_id, value) " +
                "VALUES (current_setting('exeris.tenant_id', true), ?)")) {
            stmt.bindString(0, value).executeUpdate();
        }
    }

    @Override
    protected List<String> readSentinelRows(PersistenceConnection conn) {
        List<String> rows = new ArrayList<>();
        try (QueryResult result = conn.executeQuery("SELECT value FROM tenant_docs ORDER BY value")) {
            while (result.next()) {
                rows.add(result.row().getString(0));
            }
        }
        return rows;
    }
}
