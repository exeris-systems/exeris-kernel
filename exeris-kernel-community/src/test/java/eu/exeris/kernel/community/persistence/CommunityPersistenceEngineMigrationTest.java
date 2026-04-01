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
import eu.exeris.kernel.spi.persistence.QueryResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("CommunityPersistenceEngine SQL migration startup")
class CommunityPersistenceEngineMigrationTest {

    @Test
    @DisplayName("run.migrations=true creates exeris_outbox table during engine bootstrap")
    void runMigrationsCreatesOutboxTable() {
        try (CommunityPersistenceEngine engine = new CommunityPersistenceEngine(testConfig(true));
             PersistenceConnection connection = engine.openConnection()) {
            assertThat(countTables(connection, "EXERIS_OUTBOX")).isEqualTo(1);
        }
    }

    @Test
    @DisplayName("run.migrations=false does not create exeris_outbox table")
    void skipMigrationsLeavesOutboxTableAbsent() {
        try (CommunityPersistenceEngine engine = new CommunityPersistenceEngine(testConfig(false));
             PersistenceConnection connection = engine.openConnection()) {
            assertThat(countTables(connection, "EXERIS_OUTBOX")).isEqualTo(0);
        }
    }

    private static long countTables(PersistenceConnection connection, String tableName) {
        try (var statement = connection.prepare(
                "SELECT COUNT(*) FROM information_schema.tables WHERE table_name = $1")) {
            statement.bindString(0, tableName);
            try (QueryResult result = statement.executeQuery()) {
                if (result.next()) {
                    return result.row().getLong(0);
                }
            }
        }
        return 0L;
    }

    private static PersistenceConfig testConfig(boolean runMigrations) {
        return new PersistenceConfig(
                "jdbc:h2:mem:community_migration_" + System.nanoTime() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
                "sa",
                "",
                4,
                1,
                5_000L,
                60_000L,
                600_000L,
                false,
                false,
                false,
                0,
                Map.of("run.migrations", Boolean.toString(runMigrations))
        );
    }
}
