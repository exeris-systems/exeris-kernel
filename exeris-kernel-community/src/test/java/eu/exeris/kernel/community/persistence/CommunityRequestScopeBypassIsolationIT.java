/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.community.persistence;

import eu.exeris.kernel.spi.context.KernelProviders;
import eu.exeris.kernel.spi.persistence.PersistenceConfig;
import eu.exeris.kernel.spi.persistence.PersistenceConnection;
import eu.exeris.kernel.spi.persistence.PersistenceEngine;
import eu.exeris.kernel.spi.persistence.PersistenceStatement;
import eu.exeris.kernel.spi.persistence.QueryResult;
import eu.exeris.kernel.spi.persistence.TransactionIsolation;
import eu.exeris.kernel.spi.security.ImmutableStorageContext;
import org.assertj.core.api.SoftAssertions;
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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration: what a {@code BYPASS_SCOPE_MISMATCH} actually hands the caller.
 *
 * <p>{@code openConnection()} keys the request session {@code "shared"};
 * {@code openConnection(StorageContext)} keys it by tenant. A request that
 * touches both loses the second one to {@code BYPASS_SCOPE_MISMATCH} and falls
 * through to {@code openPhysicalConnection()} — which runs no
 * {@code ConnectionInterceptor}. {@link RlsConnectionInterceptor} publishes its
 * keys with session-scoped {@code set_config(..., false)}, so that connection
 * does not arrive neutral: it carries whatever the previous borrower published.
 *
 * <p>Asserted here is the contract, not today's behaviour — no connection handed
 * to a request may carry another tenant's session key, whichever overload the
 * caller used. A benchmark cannot decide this: with no RLS policy installed,
 * nothing reads the session key and the same bypass is invisible.
 */
@Tag("integration")
@Testcontainers(disabledWithoutDocker = true)
@DisplayName("Community: a request-scope bypass must not hand over another tenant's session")
class CommunityRequestScopeBypassIsolationIT {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16");

    private static final String APP_USER = "exeris_app";
    private static final String APP_PASSWORD = "exeris_app_pw";
    private static final String TENANT_A = "tenant-a";
    private static final String TENANT_B = "tenant-b";
    private static final int POOL_SIZE = 2;

    private static final ImmutableStorageContext CTX_A = ImmutableStorageContext.shared(TENANT_A);
    private static final ImmutableStorageContext CTX_B = ImmutableStorageContext.shared(TENANT_B);

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

    @Test
    @DisplayName("a bypassed acquire inside a tenant-b request must not arrive carrying tenant-a")
    void bypassedAcquireDoesNotInheritPreviousTenantSession() {
        seedRow(CTX_A, "seed-a");
        seedRow(CTX_B, "seed-b");
        primeEveryPooledConnectionWith(CTX_A);

        // One HTTP request, tenant-b, bound exactly as CommunityHttpRequestDispatcher binds it.
        PersistenceSessionBox box =
                new PersistenceSessionBox(engine, TransactionIsolation.READ_COMMITTED, false);

        ScopedValue.where(KernelProviders.STORAGE_CONTEXT, CTX_B)
                .where(PersistenceSessionBox.REQUEST_SESSION, box)
                .run(() -> {
                    try {
                        assertRequestSessionIsHonoured();
                    } finally {
                        box.release();
                    }
                });
    }

    private static void assertRequestSessionIsHonoured() {
        // Op 1 - a repository read/write, through the context overload.
        try (PersistenceConnection scoped = engine.openConnection(CTX_B)) {
            assertThat(sessionTenantOf(scoped))
                    .as("request session must carry its own tenant")
                    .isEqualTo(TENANT_B);

            // Op 2 - a snapshot / outbox / event-log write, through the no-arg overload. This is
            // the call that used to key the session "shared", miss, and take its own connection.
            try (PersistenceConnection second = engine.openConnection()) {
                SoftAssertions.assertSoftly(softly -> {
                    softly.assertThat(backendPidOf(second))
                            .as("one request must be one connection: the no-arg overload has to "
                                + "reuse the request session, not take a second backend")
                            .isEqualTo(backendPidOf(scoped));
                    softly.assertThat(sessionTenantOf(second))
                            .as("the connection must carry this request's tenant, never the one "
                                + "the previous borrower left behind (set_config(..., false) is "
                                + "session-scoped and survives pool checkin)")
                            .isEqualTo(TENANT_B);
                    softly.assertThat(visibleRows(second))
                            .as("reads must see this tenant's rows and no other tenant's")
                            .contains("seed-b")
                            .doesNotContain("seed-a");
                });
            }
        }
    }

    /**
     * Leaves every pooled connection carrying {@code ctx}'s session keys, so a later
     * un-intercepted acquire cannot draw a neutral one by luck.
     */
    private static void primeEveryPooledConnectionWith(ImmutableStorageContext ctx) {
        List<PersistenceConnection> held = new ArrayList<>(POOL_SIZE);
        try {
            ScopedValue.where(KernelProviders.STORAGE_CONTEXT, ctx).run(() -> {
                for (int i = 0; i < POOL_SIZE; i++) {
                    held.add(engine.openConnection(ctx));
                }
            });
        } finally {
            held.forEach(PersistenceConnection::close);
        }
    }

    private static void seedRow(ImmutableStorageContext ctx, String value) {
        ScopedValue.where(KernelProviders.STORAGE_CONTEXT, ctx).run(() -> {
            try (PersistenceConnection conn = engine.openConnection(ctx);
                 PersistenceStatement stmt = conn.prepare(
                         "INSERT INTO tenant_docs(tenant_id, value) VALUES (?, ?)")) {
                stmt.bindString(0, ctx.isolationKey().orElseThrow())
                    .bindString(1, value)
                    .executeUpdate();
            }
        });
    }


    /** The PostgreSQL backend serving this connection - identity of the physical connection. */
    private static int backendPidOf(PersistenceConnection conn) {
        try (QueryResult result = conn.executeQuery("SELECT pg_backend_pid()")) {
            assertThat(result.next()).isTrue();
            return result.row().getInt(0);
        }
    }
    private static String sessionTenantOf(PersistenceConnection conn) {
        try (QueryResult result = conn.executeQuery(
                "SELECT current_setting('exeris.tenant_id', true)")) {
            assertThat(result.next()).isTrue();
            return result.row().getString(0);
        }
    }

    private static List<String> visibleRows(PersistenceConnection conn) {
        List<String> rows = new ArrayList<>();
        try (QueryResult result = conn.executeQuery("SELECT value FROM tenant_docs ORDER BY value")) {
            while (result.next()) {
                rows.add(result.row().getString(0));
            }
        }
        return rows;
    }

    private static PersistenceEngine createEngine(PostgreSQLContainer<?> container) {
        PersistenceConfig config = new PersistenceConfig(
                container.getJdbcUrl(), APP_USER, APP_PASSWORD,
                POOL_SIZE, POOL_SIZE,
                5_000L, 60_000L, 600_000L,
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
            st.execute("DROP TABLE IF EXISTS tenant_docs CASCADE");
            st.execute("CREATE TABLE tenant_docs (tenant_id TEXT NOT NULL, value TEXT NOT NULL)");
            // FORCE is load-bearing: without it the owning role is exempt from its own
            // policy and the table fails open. See RlsConnectionInterceptor's contract notes.
            st.execute("ALTER TABLE tenant_docs ENABLE ROW LEVEL SECURITY");
            st.execute("ALTER TABLE tenant_docs FORCE ROW LEVEL SECURITY");
            st.execute("DROP POLICY IF EXISTS tenant_isolation ON tenant_docs");
            st.execute("CREATE POLICY tenant_isolation ON tenant_docs "
                       + "USING (tenant_id = current_setting('exeris.tenant_id', true)) "
                       + "WITH CHECK (tenant_id = current_setting('exeris.tenant_id', true))");
            st.execute("GRANT USAGE ON SCHEMA public TO exeris_app");
            st.execute("GRANT SELECT, INSERT, UPDATE, DELETE ON tenant_docs TO exeris_app");
        } catch (SQLException e) {
            throw new IllegalStateException("Bootstrap schema failed", e);
        }
    }
}
