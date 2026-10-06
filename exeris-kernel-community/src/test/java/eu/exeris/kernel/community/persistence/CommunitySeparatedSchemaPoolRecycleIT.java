/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.persistence;

import eu.exeris.kernel.spi.context.KernelProviders;
import eu.exeris.kernel.spi.persistence.PersistenceConfig;
import eu.exeris.kernel.spi.persistence.PersistenceConnection;
import eu.exeris.kernel.spi.persistence.PersistenceEngine;
import eu.exeris.kernel.spi.persistence.QueryResult;
import eu.exeris.kernel.spi.security.ImmutableStorageContext;
import eu.exeris.kernel.spi.security.StorageContext;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration: a pooled connection recycled between tenants resolves unqualified names in the
 * schema of the tenant using it <em>now</em> (ADR-012).
 *
 * <h2>Why this exists as an integration test</h2>
 * <p>{@code RlsConnectionInterceptorTest} already asserts that the interceptor issues
 * {@code SET search_path TO <schema>, public} and that a SHARED acquire issues the {@code RESET}.
 * Those are assertions about the <em>SQL the kernel emits</em>, taken against a mock. They cannot
 * fail if the statement is emitted and PostgreSQL does something other than what the kernel assumes,
 * and they say nothing about what a <em>second</em> tenant sees on a connection the first one used.
 * This exercises the property the emission exists to produce, against a live server.
 *
 * <h2>The pool is deliberately one connection deep</h2>
 * <p>{@code persistence.perTenantPooling} defaults to {@code false}, so tenants share physical
 * connections — that default is what makes recycling reachable at all. A pool of one makes it
 * <b>certain</b>: the second acquire cannot get a different connection, so the case tests recycling
 * rather than happening to test it. A larger pool would let the two tenants land on separate
 * connections and pass while the republish did nothing.
 *
 * <h2>What this is not</h2>
 * <p>Not a row-visibility hole. RLS keys on {@code exeris.tenant_id}, which is republished on the
 * same acquire, so a stale {@code search_path} misdirects <em>name resolution</em> — an unqualified
 * table name reaching the wrong schema — rather than exposing another tenant's rows through a
 * policy-protected table. The distinction is the whole reason this is worth stating precisely.
 *
 * <h2>Execution</h2>
 * <p>Tagged {@code integration} and skipped automatically when Docker is unavailable:
 * <pre>mvn -pl exeris-kernel-community test -Dtest=CommunitySeparatedSchemaPoolRecycleIT -DincludedGroups=integration -DexcludedGroups=</pre>
 *
 * @since 0.12.0
 */
@Tag("integration")
@Testcontainers(disabledWithoutDocker = true)
@DisplayName("Community: a pooled connection recycled between tenants resolves names in the right schema")
class CommunitySeparatedSchemaPoolRecycleIT {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16");

    private static final String APP_USER = "exeris_app";
    private static final String APP_PASSWORD = "exeris_app_pw";

    private static final String TENANT_A = "tenant-a";
    private static final String TENANT_B = "tenant-b";
    private static final String SCHEMA_A = "tenant_a";
    private static final String SCHEMA_B = "tenant_b";

    /** The same unqualified name in three schemas — the row that comes back names the schema. */
    private static final String ROW_A = "row-in-tenant-a";
    private static final String ROW_B = "row-in-tenant-b";
    private static final String ROW_PUBLIC = "row-in-public";

    private static final StorageContext CTX_A = ImmutableStorageContext.separatedSchema(TENANT_A, SCHEMA_A);
    private static final StorageContext CTX_B = ImmutableStorageContext.separatedSchema(TENANT_B, SCHEMA_B);
    private static final StorageContext CTX_SHARED = ImmutableStorageContext.shared(TENANT_A);

    private static PersistenceEngine engine;

    @BeforeAll
    static void startEngine() {
        bootstrapSchemas(POSTGRES);
        engine = createSingleConnectionEngine(POSTGRES);
        engine.registerInterceptor(RlsConnectionInterceptor.INSTANCE);
    }

    @AfterAll
    static void stopEngine() {
        if (engine != null) {
            engine.close();
        }
    }

    @Nested
    @DisplayName("Recycling between two separated schemas")
    class BetweenTenants {

        @Test
        @DisplayName("the second tenant reads its own schema, not the schema the first one left behind")
        void secondTenantReadsItsOwnSchema() {
            // Both directions, in one case and on one connection: a republish that only ever ran on
            // the first acquire would still satisfy a test that checked one tenant, and so would one
            // that set the path from a constant.
            assertThat(readUnqualifiedRow(CTX_A))
                    .as("tenant-a, on a connection with no history")
                    .isEqualTo(ROW_A);
            assertThat(readUnqualifiedRow(CTX_B))
                    .as("tenant-b, on the connection tenant-a just used")
                    .isEqualTo(ROW_B);
            assertThat(readUnqualifiedRow(CTX_A))
                    .as("and back — the path follows the acquire, not the first acquire")
                    .isEqualTo(ROW_A);
        }

        @Test
        @DisplayName("current_schema reports the acquiring tenant's schema, not the previous one's")
        void currentSchemaFollowsTheAcquire() {
            readUnqualifiedRow(CTX_A);

            assertThat(readScalar(CTX_B, "SELECT current_schema()"))
                    .as("the server's own view of where an unqualified name resolves")
                    .isEqualTo(SCHEMA_B);
        }
    }

    @Nested
    @DisplayName("Recycling from a separated schema to a shared one")
    class BackToShared {

        @Test
        @DisplayName("a SHARED acquire resets the path a SEPARATED_SCHEMA acquire left set")
        void sharedAcquireResetsTheSearchPath() {
            // The direction the RESET arm exists for, and the one a SEPARATED_SCHEMA-only test
            // cannot reach: SHARED sets no path of its own, so without the reset it inherits one.
            readUnqualifiedRow(CTX_A);

            assertThat(readUnqualifiedRow(CTX_SHARED))
                    .as("a shared-scope request must not still be pointed at a tenant's schema")
                    .isEqualTo(ROW_PUBLIC);
        }
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private static String readUnqualifiedRow(StorageContext context) {
        return readScalar(context, "SELECT value FROM docs");
    }

    private static String readScalar(StorageContext context, String sql) {
        return ScopedValue.where(KernelProviders.STORAGE_CONTEXT, context).call(() -> {
            try (PersistenceConnection conn = engine.openConnection(context);
                 QueryResult result = conn.executeQuery(sql)) {
                assertThat(result.next()).as("query returned no row: %s", sql).isTrue();
                return result.row().getString(0);
            }
        });
    }

    private static PersistenceEngine createSingleConnectionEngine(PostgreSQLContainer<?> container) {
        PersistenceConfig config = new PersistenceConfig(
                container.getJdbcUrl(),
                APP_USER,
                APP_PASSWORD,
                1,              // maxPoolSize — one connection, so recycling is certain
                1,
                5_000L,
                60_000L,
                600_000L,
                true,
                false,          // perTenantPooling — the default, and what makes tenants share
                false,
                0,
                Map.of()
        );
        return new CommunityPersistenceProvider().createEngine(config);
    }

    private static void bootstrapSchemas(PostgreSQLContainer<?> container) {
        try (Connection conn = DriverManager.getConnection(
                     container.getJdbcUrl(), container.getUsername(), container.getPassword());
             Statement st = conn.createStatement()) {
            st.execute("DO $$ BEGIN "
                    + "  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = '" + APP_USER + "') THEN "
                    + "    CREATE ROLE " + APP_USER + " LOGIN NOSUPERUSER PASSWORD '" + APP_PASSWORD + "'; "
                    + "  END IF; "
                    + "END $$");
            createDocsTable(st, SCHEMA_A, ROW_A);
            createDocsTable(st, SCHEMA_B, ROW_B);
            createDocsTable(st, "public", ROW_PUBLIC);
        } catch (SQLException e) {
            throw new IllegalStateException("Bootstrap schemas failed", e);
        }
    }

    private static void createDocsTable(Statement st, String schema, String seedRow) throws SQLException {
        if (!"public".equals(schema)) {
            st.execute("CREATE SCHEMA IF NOT EXISTS " + schema);
        }
        st.execute("DROP TABLE IF EXISTS " + schema + ".docs CASCADE");
        st.execute("CREATE TABLE " + schema + ".docs (value TEXT NOT NULL)");
        st.execute("INSERT INTO " + schema + ".docs(value) VALUES ('" + seedRow + "')");
        st.execute("GRANT USAGE ON SCHEMA " + schema + " TO " + APP_USER);
        st.execute("GRANT SELECT ON " + schema + ".docs TO " + APP_USER);
    }
}
