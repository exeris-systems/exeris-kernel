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
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * A statement that fails must not poison the connection it ran on.
 *
 * <h2>Why this needs its own fixture</h2>
 * <p>The Community pool baseline is {@code autoCommit=false}, so <em>every</em> statement opens a real
 * database transaction whether or not an SPI caller opened one — which is why
 * {@code commitStandaloneWriteIfNeeded} exists for the success path. Its counterpart on the failure
 * path was missing: {@code close()} rolled back only when the SPI-level {@code inTransaction} flag was
 * set, so a standalone write that threw returned the physical connection to the pool inside an aborted
 * transaction. The next request to receive it died on its first statement — including the RLS
 * interceptor's, before any application code ran.
 *
 * <p>A constraint violation is the cheapest way to reach that, but the ordinary way is an RLS
 * {@code WITH CHECK} rejection: the security control working as designed poisoned a pooled connection
 * for an unrelated later request.
 *
 * <p><b>The pool is pinned to one connection.</b> That is the whole point of this fixture rather than
 * a case in an existing suite. With a larger pool whether the poisoned connection comes back is a
 * matter of assignment order, and {@code CommunityPersistenceSharedScopeIT} was passing on exactly
 * that luck until an unrelated change added a statement to the acquire path.
 */
@Tag("integration")
@Testcontainers(disabledWithoutDocker = true)
@DisplayName("Community persistence: a failed write returns a usable connection to the pool")
class CommunityPersistenceFailedWriteReturnIT {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16");

    /** One connection, so "the next acquire" is necessarily the one the failed write just used. */
    private static final int SINGLE_CONNECTION_POOL = 1;

    private static PersistenceEngine engine;

    @BeforeAll
    static void startEngine() {
        bootstrapSchema();
        engine = createEngine();
    }

    @AfterAll
    static void stopEngine() {
        if (engine != null) {
            engine.close();
        }
    }

    @Test
    @DisplayName("a rejected standalone write leaves the next acquire able to run")
    void rejectedWriteDoesNotPoisonTheConnection() {
        assertThatThrownBy(this::insertDuplicateKey)
                .as("the fixture depends on this write actually failing; a schema that accepted it "
                    + "would make everything below vacuous")
                .isInstanceOf(Exception.class);

        assertThatCode(this::countRows)
                .as("the failed write ran with no SPI transaction open, so close() skipped its "
                    + "rollback and handed the pool a connection still inside an aborted "
                    + "transaction — this next acquire then failed on its first statement with "
                    + "'current transaction is aborted, commands ignored until end of transaction "
                    + "block', before reaching any application SQL")
                .doesNotThrowAnyException();

        assertThat(countRows())
                .as("and the rejected write left nothing behind")
                .isEqualTo(1L);
    }

    @Test
    @DisplayName("a rejected write inside an explicit transaction is unaffected")
    void rejectedWriteInsideExplicitTransactionStillRecovers() {
        // The path that already worked, kept as a guard: the fix widened when close() rolls back, and
        // must not have narrowed anything. A failure here would mean the explicit-transaction case
        // regressed while the standalone one was being repaired.
        try (PersistenceConnection conn = engine.openConnection()) {
            conn.beginTransaction();
            assertThatThrownBy(() -> {
                try (PersistenceStatement stmt = conn.prepare(
                        "INSERT INTO pool_probe(id, value) VALUES (?, ?)")) {
                    stmt.bindLong(0, 1L).bindString(1, "duplicate").executeUpdate();
                }
            }).isInstanceOf(Exception.class);
        }

        assertThatCode(this::countRows).doesNotThrowAnyException();
    }

    // =========================================================================
    // Fixture
    // =========================================================================

    /** No {@code beginTransaction}: the standalone-write path is the one that was broken. */
    private void insertDuplicateKey() {
        try (PersistenceConnection conn = engine.openConnection();
             PersistenceStatement stmt = conn.prepare(
                     "INSERT INTO pool_probe(id, value) VALUES (?, ?)")) {
            stmt.bindLong(0, 1L).bindString(1, "duplicate").executeUpdate();
        }
    }

    private long countRows() {
        try (PersistenceConnection conn = engine.openConnection();
             PersistenceStatement stmt = conn.prepare("SELECT count(*) FROM pool_probe");
             QueryResult result = stmt.executeQuery()) {
            return result.next() ? result.row().getLong(0) : -1L;
        }
    }

    private static PersistenceEngine createEngine() {
        PersistenceConfig config = new PersistenceConfig(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword(),
                SINGLE_CONNECTION_POOL, SINGLE_CONNECTION_POOL, 5_000L, 60_000L, 600_000L,
                false, false, false, 0, Map.of());
        return new CommunityPersistenceProvider().createEngine(config);
    }

    private static void bootstrapSchema() {
        try (Connection conn = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             Statement st = conn.createStatement()) {
            st.execute("DROP TABLE IF EXISTS pool_probe CASCADE");
            st.execute("CREATE TABLE pool_probe (id BIGINT PRIMARY KEY, value TEXT NOT NULL)");
            st.execute("INSERT INTO pool_probe(id, value) VALUES (1, 'seed')");
        } catch (SQLException e) {
            throw new IllegalStateException("Bootstrap schema failed", e);
        }
    }
}
