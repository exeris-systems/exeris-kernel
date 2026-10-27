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
import eu.exeris.kernel.spi.security.ImmutableStorageContext;
import eu.exeris.kernel.spi.security.StorageContext;
import eu.exeris.kernel.tck.contract.persistence.AbstractSharedScopeAccessMatrixTck;
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
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * Community binding of {@link AbstractSharedScopeAccessMatrixTck} on {@code uuid} owner and scope columns,
 * against a live PostgreSQL instance.
 *
 * <p>This is the {@code uuid} form of the reference policy {@code RlsConnectionInterceptor} publishes. The
 * {@code TEXT} form does not transfer to such a column, so the matrix is bound here with the form a
 * {@code uuid} deployment actually installs.
 *
 * <h2>The conforming policy</h2>
 * <ul>
 *   <li>The tenant arm casts the session key with an unguarded {@code ::uuid}. An unpublished tenant is
 *       published as {@code ''}, which is not a uuid, so the cast raises instead of matching: a tenant-less
 *       context is refused on read and on write without a helper function.</li>
 *   <li>The tag check carries no {@code COALESCE(shared_scope, '')}: a {@code uuid} column cannot hold
 *       {@code ''}, so {@code NULL} is the only tenant-private tag. A published scope admits {@code NULL}
 *       and itself; a cleared scope admits {@code NULL} only.</li>
 *   <li>The read arm is the additive {@code FOR SELECT} policy, so the widening never reaches an
 *       {@code UPDATE} or a {@code DELETE}, which see the owner policy alone.</li>
 * </ul>
 *
 * <p>The tag column is nullable on purpose: {@code NULL} is the private tag, and a {@code NOT NULL}
 * column would leave the private arm of the check unreachable.
 *
 * <h2>Execution</h2>
 * <pre>mvn -pl exeris-kernel-community -am test -DincludedGroups=integration -DexcludedGroups=</pre>
 *
 * @since 0.13.0
 */
@Tag("integration")
@Testcontainers(disabledWithoutDocker = true)
@DisplayName("Community persistence shared-scope access matrix — live PostgreSQL binding on uuid keys")
class CommunityPersistenceSharedScopeUuidIT extends AbstractSharedScopeAccessMatrixTck {

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
            st.execute("TRUNCATE scoped_docs_uuid");
        } catch (SQLException e) {
            throw new IllegalStateException("Truncate failed", e);
        }
        seedMatrixRows();
    }

    @Override
    protected void seed(StorageContext ctx, String owner, String sharedScope, String value) {
        try (PersistenceConnection conn = engine.openConnection(ctx);
             PersistenceStatement stmt = conn.prepare(
                     "INSERT INTO scoped_docs_uuid(tenant_id, shared_scope, value) VALUES (?, ?, ?)")) {
            stmt.bindUuid(0, UUID.fromString(owner));
            bindTag(stmt, 1, sharedScope);
            stmt.bindString(2, value).executeUpdate();
        }
    }

    @Override
    protected List<String> readVisible(StorageContext ctx) {
        List<String> rows = new ArrayList<>();
        try (PersistenceConnection conn = engine.openConnection(ctx);
             QueryResult result = conn.executeQuery("SELECT value FROM scoped_docs_uuid")) {
            while (result.next()) {
                rows.add(result.row().getString(0));
            }
        }
        return rows;
    }

    @Override
    protected int updateValue(StorageContext ctx, String value, String newValue) {
        try (PersistenceConnection conn = engine.openConnection(ctx);
             PersistenceStatement stmt = conn.prepare("UPDATE scoped_docs_uuid SET value = ? WHERE value = ?")) {
            return Math.toIntExact(stmt.bindString(0, newValue).bindString(1, value).executeUpdate());
        }
    }

    @Override
    protected String ownerA() {
        return "00000000-0000-0000-0000-0000000000a1";
    }

    @Override
    protected String ownerB() {
        return "00000000-0000-0000-0000-0000000000b1";
    }

    @Override
    protected String sharedScope() {
        return "00000000-0000-0000-0000-00000000005a";
    }

    @Override
    protected String foreignScope() {
        return "00000000-0000-0000-0000-00000000005b";
    }

    /** A valid uuid distinct from both owners: the column cannot hold {@code ''}, and the cast raises regardless. */
    @Override
    protected String tenantlessOwner() {
        return "00000000-0000-0000-0000-0000000000ff";
    }

    @Test
    @DisplayName("an absent tenant raises 22P02 — the unguarded uuid cast rejects the empty key")
    void tenantless_read_cause_carries_invalid_text_representation() {
        Throwable failure = catchThrowable(
                () -> readVisible(ImmutableStorageContext.GLOBAL));

        assertThat(failure).isNotNull();
        String sqlState = null;
        for (Throwable t = failure; t != null; t = t.getCause()) {
            if (t instanceof SQLException sql) {
                sqlState = sql.getSQLState();
                break;
            }
        }
        assertThat(sqlState)
                .as("the cast raising is what refuses the context; an empty result or a different cause "
                        + "would mean the tenant arm is guarded")
                .isEqualTo("22P02");
    }

    @Override
    protected int retagScope(StorageContext ctx, String value, String newSharedScope) {
        try (PersistenceConnection conn = engine.openConnection(ctx);
             PersistenceStatement stmt = conn.prepare("UPDATE scoped_docs_uuid SET shared_scope = ? WHERE value = ?")) {
            bindTag(stmt, 0, newSharedScope);
            return Math.toIntExact(stmt.bindString(1, value).executeUpdate());
        }
    }

    /** Binds a tag, with {@code null} as SQL {@code NULL}, the private tag of a {@code uuid} column. */
    private static void bindTag(PersistenceStatement stmt, int index, String tag) {
        stmt.bindUuid(index, tag == null ? null : UUID.fromString(tag));
    }

    @Override
    protected int reassignOwner(StorageContext ctx, String value, String newOwner) {
        try (PersistenceConnection conn = engine.openConnection(ctx);
             PersistenceStatement stmt = conn.prepare("UPDATE scoped_docs_uuid SET tenant_id = ? WHERE value = ?")) {
            return Math.toIntExact(stmt.bindUuid(0, UUID.fromString(newOwner)).bindString(1, value).executeUpdate());
        }
    }

    @Override
    protected int delete(StorageContext ctx, String value) {
        try (PersistenceConnection conn = engine.openConnection(ctx);
             PersistenceStatement stmt = conn.prepare("DELETE FROM scoped_docs_uuid WHERE value = ?")) {
            return Math.toIntExact(stmt.bindString(0, value).executeUpdate());
        }
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
            st.execute("DROP TABLE IF EXISTS scoped_docs_uuid CASCADE");
            st.execute("CREATE TABLE scoped_docs_uuid (tenant_id UUID NOT NULL, "
                    + "shared_scope UUID, value TEXT NOT NULL)");
            st.execute("ALTER TABLE scoped_docs_uuid ENABLE ROW LEVEL SECURITY");
            st.execute("ALTER TABLE scoped_docs_uuid FORCE ROW LEVEL SECURITY");
            st.execute("CREATE POLICY tenant_isolation ON scoped_docs_uuid "
                    + "USING (" + RlsReferencePolicy.TENANT_ARM_UUID + ") "
                    + "WITH CHECK (" + RlsReferencePolicy.TENANT_ARM_UUID + " "
                    + "AND " + RlsReferencePolicy.SCOPE_CHECK_UUID + ")");
            st.execute("CREATE POLICY shared_scope_read ON scoped_docs_uuid FOR SELECT "
                    + "USING (" + RlsReferencePolicy.SCOPE_READ_UUID + ")");
            st.execute("GRANT USAGE ON SCHEMA public TO exeris_app");
            st.execute("GRANT SELECT, INSERT, UPDATE, DELETE ON scoped_docs_uuid TO exeris_app");
        } catch (SQLException e) {
            throw new IllegalStateException("Bootstrap schema failed", e);
        }
    }
}
