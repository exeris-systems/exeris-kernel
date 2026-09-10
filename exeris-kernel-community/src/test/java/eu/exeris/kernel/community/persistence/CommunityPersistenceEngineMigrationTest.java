/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.persistence;

import org.junit.jupiter.api.Nested;

import java.util.List;

import java.util.ArrayList;

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

    /**
     * Every migration resource on the classpath is registered to run.
     *
     * <p>The list is hand-maintained, so a new file under {@code db/migration/} is inert until someone
     * remembers to add it — and nothing about that failure is visible until a query hits the missing
     * column. That is not hypothetical: {@code V0.11.2} was written, shipped in the resource directory,
     * and left unregistered, and the whole default build stayed green. Only the Testcontainers gate,
     * which does not run in {@code mvn clean install}, said {@code column … does not exist}.
     */
    @Test
    @DisplayName("every db/migration resource is registered to run — an unlisted file is inert")
    void everyMigrationResourceIsRegistered() {
        List<String> onDisk = migrationResourcesOnClasspath();

        assertThat(onDisk)
                .as("the enumeration itself must find something, or this test passes vacuously")
                .isNotEmpty();
        assertThat(CommunityPersistenceEngine.MIGRATION_RESOURCES)
                .as("a migration file that is not listed here never runs, and the first symptom is a "
                        + "missing column at query time in a gate the default build excludes")
                .containsExactlyInAnyOrderElementsOf(onDisk);
    }

    /**
     * The saga-state ALTERs actually execute — the three v0.11 columns exist after bootstrap.
     *
     * <p>Complements the check above: that one proves the files are wired, this one proves they run.
     * Before this, no default-build test asserted that any of the three v0.11 migrations had any
     * effect, on H2 or anywhere else.
     */
    @Test
    @DisplayName("the v0.11 saga-state columns exist after migration")
    void sagaStateCarriesTheV011Columns() {
        try (CommunityPersistenceEngine engine = new CommunityPersistenceEngine(testConfig(true));
             PersistenceConnection connection = engine.openConnection()) {
            assertThat(countColumn(connection, "EXERIS_SAGA_STATE", "STEP_NAME"))
                    .as("V0.11.0 (ADR-062)").isEqualTo(1);
            assertThat(countColumn(connection, "EXERIS_SAGA_STATE", "DEFINITION_VERSION"))
                    .as("V0.11.1 (ADR-064)").isEqualTo(1);
            assertThat(countColumn(connection, "EXERIS_SAGA_STATE", "COMPENSATION_STEP_NAMES"))
                    .as("V0.11.2 (ADR-064 A5)").isEqualTo(1);
        }
    }

    private static List<String> migrationResourcesOnClasspath() {
        java.net.URL directory = CommunityPersistenceEngine.class.getResource("/db/migration");
        assertThat(directory)
                .as("db/migration must be on the test classpath for this check to mean anything")
                .isNotNull();
        java.io.File[] files = new java.io.File(java.net.URI.create(directory.toString())).listFiles();
        assertThat(files).as("db/migration must be readable as a directory").isNotNull();
        List<String> paths = new ArrayList<>();
        for (java.io.File file : files) {
            if (file.getName().endsWith(".sql")) {
                paths.add("db/migration/" + file.getName());
            }
        }
        return paths;
    }

    private static long countColumn(PersistenceConnection connection, String table, String column) {
        try (var statement = connection.prepare(
                "SELECT COUNT(*) FROM information_schema.columns "
                        + "WHERE table_name = $1 AND column_name = $2")) {
            statement.bindString(0, table);
            statement.bindString(1, column);
            try (QueryResult result = statement.executeQuery()) {
                if (result.next()) {
                    return result.row().getLong(0);
                }
            }
        }
        return 0L;
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

    @Nested
    @DisplayName("Migration ordering")
    class Ordering {

        @Test
        @DisplayName("migrations run in version order, not string order")
        void migrationsRunInVersionOrder() {
            List<String> shuffled = new ArrayList<>(List.of(
                    "db/migration/V0.11.0__add_saga_step_name.sql",
                    "db/migration/V0.5.0__create_outbox.sql",
                    "db/migration/V0.10.0__create_event_log.sql",
                    "db/migration/V0.7.0__create_saga_state.sql"));

            shuffled.sort(CommunityPersistenceMigrationRunner.MIGRATION_ORDER);

            assertThat(shuffled)
                    .as("string order puts V0.10.0 and V0.11.0 ahead of V0.5.0, because '1' < '5'. "
                            + "That was invisible while every script only created its own table with "
                            + "IF NOT EXISTS; the first ALTER against an earlier script's table fails "
                            + "outright, which is how it was found")
                    .containsExactly(
                            "db/migration/V0.5.0__create_outbox.sql",
                            "db/migration/V0.7.0__create_saga_state.sql",
                            "db/migration/V0.10.0__create_event_log.sql",
                            "db/migration/V0.11.0__add_saga_step_name.sql");
        }

        @Test
        @DisplayName("an unversioned resource sorts last rather than failing the boot")
        void unversionedResourceSortsLast() {
            List<String> paths = new ArrayList<>(List.of(
                    "db/migration/readme.txt",
                    "db/migration/V0.7.0__create_saga_state.sql"));

            paths.sort(CommunityPersistenceMigrationRunner.MIGRATION_ORDER);

            assertThat(paths)
                    .as("a migration runner is not the right place to fail a boot over a file name")
                    .containsExactly(
                            "db/migration/V0.7.0__create_saga_state.sql",
                            "db/migration/readme.txt");
        }
    }
}
