/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.community.persistence;

import eu.exeris.kernel.spi.exceptions.persistence.PersistenceProviderException;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Integration: the shared-scope access matrix (ADR-012 §4b.4) against a live PostgreSQL instance.
 *
 * <h2>What this proves</h2>
 * <ul>
 *   <li><b>Read widens</b> — two tenants carrying the same {@code sharedScopeKey} see each other's rows
 *       in the shared partition.</li>
 *   <li><b>Write stays pinned</b> — a tenant inside the shared partition still cannot write a row owned
 *       by another tenant. Reads widening does not relax writes.</li>
 *   <li><b>No pool bleed</b> — the security-critical one. After a request that declared a shared scope,
 *       a later request on a recycled connection that declares none must not inherit the widened
 *       visibility. {@code set_config(..., false)} is session-scoped, so this only holds because the
 *       interceptor publishes {@code ""} rather than skipping the statement.</li>
 *   <li><b>Tenant-private is untouched</b> — a context with no shared scope behaves exactly as before
 *       the accessor existed.</li>
 * </ul>
 *
 * <h2>What the kernel does and does not own</h2>
 * <p>The kernel publishes {@code exeris.tenant_id} and {@code exeris.shared_scope}; the policy that
 * consumes them is the deployment's. The policy below is therefore both a fixture and the normative
 * example of a conforming one — note that {@code WITH CHECK} is identical to the tenant-private policy.
 * Owner-scoped write is what that clause already expresses.
 *
 * <p>Cross-tenant <em>mutation</em> of another owner's row is explicitly out of contract scope
 * (ADR-012 §4b.4) and is asserted here as denied, not as a supported capability.
 *
 * <h2>Execution</h2>
 * <pre>mvn -pl exeris-kernel-community test -Dtest=CommunityPersistenceSharedScopeIT -DincludedGroups=integration -DexcludedGroups=</pre>
 *
 * @since 0.11.0
 */
@Tag("integration")
@Testcontainers(disabledWithoutDocker = true)
@DisplayName("Community persistence shared-scope access matrix — Testcontainers integration")
class CommunityPersistenceSharedScopeIT {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16");

    private static PersistenceEngine engine;

    private static final String APP_USER = "exeris_app";
    private static final String APP_PASSWORD = "exeris_app_pw";

    private static final String TENANT_A = "tenant-a";
    private static final String TENANT_B = "tenant-b";
    private static final String WORLD = "world-alpha";

    /** Both tenants inside the same shared partition. */
    private static final StorageContext CTX_A_SHARED =
            ImmutableStorageContext.shared(TENANT_A).withSharedScope(WORLD);
    private static final StorageContext CTX_B_SHARED =
            ImmutableStorageContext.shared(TENANT_B).withSharedScope(WORLD);

    /** Same tenant, no shared scope declared — the tenant-private default. */
    private static final StorageContext CTX_A_PRIVATE = ImmutableStorageContext.shared(TENANT_A);

    @BeforeAll
    static void startEngine() {
        bootstrapSchema(POSTGRES);
        engine = createEngine(POSTGRES);
        engine.registerInterceptor(RlsConnectionInterceptor.INSTANCE);

        insertRow(CTX_A_SHARED, WORLD, "a-in-world");
        insertRow(CTX_B_SHARED, WORLD, "b-in-world");
        insertRow(CTX_A_PRIVATE, null, "a-private");
    }

    @AfterAll
    static void stopEngine() {
        if (engine != null) {
            engine.close();
        }
    }

    @Test
    @DisplayName("read widens — a tenant sees a partition-mate's row inside the shared scope")
    void readWidensWithinSharedScope() {
        assertThat(readVisible(CTX_A_SHARED))
                .as("A declared the shared partition, so B's row in it becomes visible")
                .contains("a-in-world", "b-in-world");

        assertThat(readVisible(CTX_B_SHARED))
                .as("symmetric — the widening is a property of the partition, not of one tenant")
                .contains("a-in-world", "b-in-world");
    }

    @Test
    @DisplayName("write stays pinned — a tenant cannot write a row owned by a partition-mate")
    void writeStaysPinnedToOwner() {
        assertThatThrownBy(() -> insertRow(CTX_A_SHARED, WORLD, "a-forging-b", TENANT_B))
                .as("reads widening must not relax writes: WITH CHECK still pins owner = "
                        + "current_setting('exeris.tenant_id'), so A forging a B-owned row is refused "
                        + "even though A can read B's rows (ADR-012 §4b.4)")
                .isInstanceOf(PersistenceProviderException.class);

        assertThat(readVisible(CTX_B_SHARED))
                .as("and nothing was written")
                .doesNotContain("a-forging-b");
    }

    @Test
    @DisplayName("no pool bleed — a later request without a shared scope does not inherit the widening")
    void sharedScopeDoesNotSurviveOntoAnUnscopedRequest() {
        // Warm the pool with a widened request first, so a recycled connection carries the setting.
        assertThat(readVisible(CTX_A_SHARED)).contains("b-in-world");

        assertThat(readVisible(CTX_A_PRIVATE))
                .as("the same tenant, now declaring no shared scope, must fall back to tenant-private. "
                        + "set_config(..., false) is session-scoped, so this only holds because the "
                        + "interceptor publishes \"\" instead of skipping the statement — skipping it "
                        + "would silently widen a request that never asked to participate")
                .contains("a-in-world", "a-private")
                .doesNotContain("b-in-world");
    }

    @Test
    @DisplayName("tenant-private is unchanged — no shared scope means the pre-existing behaviour")
    void tenantPrivateRemainsIsolated() {
        assertThat(readVisible(CTX_A_PRIVATE))
                .as("every row A owns, and nothing of B's")
                .contains("a-private", "a-in-world")
                .doesNotContain("b-in-world");
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private static void insertRow(StorageContext ctx, String sharedScope, String value) {
        insertRow(ctx, sharedScope, value, ctx.isolationKey().orElseThrow());
    }

    /** {@code owner} is separate from the context so a forged-owner write can be attempted. */
    private static void insertRow(StorageContext ctx, String sharedScope, String value, String owner) {
        try (PersistenceConnection conn = engine.openConnection(ctx);
             PersistenceStatement stmt = conn.prepare(
                     "INSERT INTO scoped_docs(tenant_id, shared_scope, value) VALUES (?, ?, ?)")) {
            stmt.bindString(0, owner)
                    .bindString(1, sharedScope == null ? "" : sharedScope)
                    .bindString(2, value)
                    .executeUpdate();
        }
    }

    private static List<String> readVisible(StorageContext ctx) {
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
            st.execute("CREATE TABLE scoped_docs ("
                    + "tenant_id TEXT NOT NULL, shared_scope TEXT NOT NULL DEFAULT '', value TEXT NOT NULL)");
            st.execute("ALTER TABLE scoped_docs ENABLE ROW LEVEL SECURITY");
            st.execute("ALTER TABLE scoped_docs FORCE ROW LEVEL SECURITY");
            st.execute("DROP POLICY IF EXISTS shared_scope_isolation ON scoped_docs");
            // The normative conforming policy (ADR-012 §4b.4). USING widens; WITH CHECK is byte-for-byte
            // the tenant-private clause. The NULLIF guard is what keeps an absent/cleared shared scope
            // from matching rows whose shared_scope column is also empty.
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
