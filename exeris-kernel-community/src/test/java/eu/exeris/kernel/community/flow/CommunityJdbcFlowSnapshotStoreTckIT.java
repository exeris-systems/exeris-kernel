/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.community.flow;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import eu.exeris.kernel.spi.flow.model.FlowSnapshotStore;
import eu.exeris.kernel.tck.contract.flow.AbstractDistributedFlowSnapshotStoreTck;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/**
 * Community binding for {@link AbstractDistributedFlowSnapshotStoreTck} using a real
 * Postgres 16 instance via Testcontainers and a HikariCP pool.
 *
 * <p>The shared schema is bootstrapped once per class via the v0.7.0 migration script;
 * each test starts with a fresh empty store created against the same database. The
 * {@code reopenStore} method returns a brand-new {@code JdbcFlowSnapshotStore} backed
 * by the same DataSource — that exercises the cross-restart contract without bouncing
 * the container.
 */
@Tag("integration")
@Testcontainers(disabledWithoutDocker = true)
@DisplayName("Community: JdbcFlowSnapshotStore distributed TCK (PostgreSQL)")
class CommunityJdbcFlowSnapshotStoreTckIT extends AbstractDistributedFlowSnapshotStoreTck {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16");

    private static HikariDataSource pool;

    @BeforeAll
    static void bootstrap() throws SQLException {
        HikariConfig cfg = new HikariConfig();
        cfg.setJdbcUrl(POSTGRES.getJdbcUrl());
        cfg.setUsername(POSTGRES.getUsername());
        cfg.setPassword(POSTGRES.getPassword());
        cfg.setMaximumPoolSize(8);
        pool = new HikariDataSource(cfg);

        String migrationSql = readMigrationResource("db/migration/V0.7.0__create_saga_state.sql");
        try (Connection conn = pool.getConnection()) {
            for (String stmt : splitStatements(migrationSql)) {
                try (Statement s = conn.createStatement()) {
                    s.execute(stmt);
                }
            }
        }
    }

    @AfterAll
    static void teardown() {
        if (pool != null) {
            pool.close();
            pool = null;
        }
    }

    @Override
    protected FlowSnapshotStore createStore() {
        truncateSagaState();
        return new JdbcFlowSnapshotStore(pool, "tck-engine");
    }

    @Override
    protected FlowSnapshotStore reopenStore(FlowSnapshotStore current) {
        // Same DataSource (i.e., same database) but a fresh store instance — the
        // contract is "data outlives a kernel restart", which the shared pool simulates
        // without bouncing the container.
        return new JdbcFlowSnapshotStore(pool, "tck-engine-restarted");
    }

    private static void truncateSagaState() {
        try (Connection conn = pool.getConnection();
             Statement s = conn.createStatement()) {
            s.execute("TRUNCATE TABLE exeris_saga_state");
        } catch (SQLException ex) {
            throw new IllegalStateException("Failed to truncate exeris_saga_state", ex);
        }
    }

    private static String readMigrationResource(String resourcePath) {
        ClassLoader cl = Thread.currentThread().getContextClassLoader();
        try (InputStream in = cl.getResourceAsStream(resourcePath)) {
            if (in == null) {
                throw new IllegalStateException("Missing SQL migration resource: " + resourcePath);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException ioEx) {
            throw new UncheckedIOException("Failed to read migration: " + resourcePath, ioEx);
        }
    }

    private static List<String> splitStatements(String sql) {
        List<String> out = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inLineComment = false;
        for (int i = 0; i < sql.length(); i++) {
            char c = sql.charAt(i);
            if (inLineComment) {
                if (c == '\n') {
                    inLineComment = false;
                }
                continue;
            }
            if (c == '-' && i + 1 < sql.length() && sql.charAt(i + 1) == '-') {
                inLineComment = true;
                i++;
                continue;
            }
            if (c == ';') {
                String trimmed = current.toString().trim();
                if (!trimmed.isEmpty()) {
                    out.add(trimmed);
                }
                current.setLength(0);
                continue;
            }
            current.append(c);
        }
        String tail = current.toString().trim();
        if (!tail.isEmpty()) {
            out.add(tail);
        }
        return out;
    }

    @SuppressWarnings({"PMD.UnusedPrivateMethod", "unused"})
    private static DataSource sharedDataSource() {
        return pool;
    }
}
