/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.persistence;

import eu.exeris.kernel.spi.flow.model.FlowSnapshot;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A saga parked under an older schema survives the upgrade (ADR-073 merge gate).
 *
 * <p>Every other migration test in this repository starts from an <b>empty</b> database, which is
 * the one case that cannot fail the way the ROADMAP entry describes: *"a schema change breaks
 * in-flight sagas on upgrade"*. Breaking them needs a row that already exists. These cases write one
 * under the {@code V0.7.0} shape — the columns the later migrations add are absent — then run the
 * real migration set over it and read the row back.
 *
 * <p>Deliberately the shipped {@code MIGRATION_RESOURCES} and not a fixture list. The claim under
 * test is about the files an upgrade actually applies, and a purpose-built pair of migrations would
 * prove the runner works while saying nothing about them.
 *
 * <p>H2 in PostgreSQL mode, matching the ledger suite. The migrations are written to be portable
 * across both engines and say so at their own declaration sites; running them here is also what
 * keeps that claim honest.
 *
 * @since 0.12.0
 */
@DisplayName("Community: an in-flight saga survives a schema upgrade (ADR-073)")
class CommunitySchemaUpgradeTest {

    private static final String PROVIDER = "postgres-community";
    private static final String SAGA_TABLE = "exeris_saga_state";
    private static final String DEFINITION = "checkout-saga";
    private static final long ID_MOST = 0x0123456789ABCDEFL;
    private static final long ID_LEAST = 0x76543210FEDCBA98L;

    /** The saga table exactly as {@code V0.7.0} left it — before step names, version or identities. */
    private static final String V07_SAGA_STATE = """
            CREATE TABLE exeris_saga_state (
                instance_id_most    BIGINT NOT NULL,
                instance_id_least   BIGINT NOT NULL,
                definition_name     TEXT NOT NULL,
                current_step        INT NOT NULL,
                state               TEXT NOT NULL,
                last_update         TIMESTAMP WITH TIME ZONE NOT NULL,
                timeout_at          TIMESTAMP WITH TIME ZONE,
                compensation_stack  BYTEA NOT NULL,
                stack_pointer       INT NOT NULL,
                opaque_state        BYTEA,
                schema_version      BIGINT NOT NULL DEFAULT 1,
                PRIMARY KEY (instance_id_most, instance_id_least)
            )""";

    @Nested
    @DisplayName("The row written before the columns existed")
    class ExistingRow {

        @Test
        @DisplayName("survives the upgrade with every value it was parked with")
        void rowSurvivesTheUpgrade() throws SQLException {
            DataSource ds = databaseAtV07();

            upgrade(ds);

            try (Connection c = ds.getConnection();
                 Statement s = c.createStatement();
                 ResultSet r = s.executeQuery("SELECT definition_name, current_step, state, "
                         + "stack_pointer FROM " + SAGA_TABLE)) {
                assertThat(r.next()).as("the parked saga MUST still be there").isTrue();
                assertThat(r.getString("definition_name")).isEqualTo(DEFINITION);
                assertThat(r.getInt("current_step")).isEqualTo(3);
                assertThat(r.getString("state")).isEqualTo("PARKED");
                assertThat(r.getInt("stack_pointer")).isEqualTo(2);
                assertThat(r.next()).as("and MUST NOT have been duplicated").isFalse();
            }
        }

        @Test
        @DisplayName("backfills definition_version to VERSION_ABSENT, not to the first version")
        void versionBackfillsToAbsent() throws SQLException {
            // V0.11.1 calls the 0 load-bearing and it is: definition versions start at 1, so 0 can
            // never name a real one, and a row carrying it is refused fail-closed on resume. A
            // DEFAULT 1 would instead assert that every saga parked before the column existed
            // belongs to the first version — the guess ADR-064 exists to stop making, applied
            // silently to rows nobody looked at.
            DataSource ds = databaseAtV07();

            upgrade(ds);

            assertThat(intColumn(ds, "definition_version"))
                    .as("a pre-0.11.1 row records no version, and says so")
                    .isEqualTo(FlowSnapshot.VERSION_ABSENT);
        }

        @Test
        @DisplayName("leaves compensation_step_names NULL, which is the opposite choice and correct")
        void identitiesBackfillNull() throws SQLException {
            // V0.11.2 took the other option from its immediate predecessor on purpose: a BYTEA can
            // represent NULL, so absence is carried by the read rather than by a sentinel. Asserted
            // because "both columns backfill" would pass against either choice, and the two
            // migrations disagreeing is what makes each one deliberate.
            DataSource ds = databaseAtV07();

            upgrade(ds);

            try (Connection c = ds.getConnection();
                 Statement s = c.createStatement();
                 ResultSet r = s.executeQuery(
                         "SELECT compensation_step_names FROM " + SAGA_TABLE)) {
                assertThat(r.next()).isTrue();
                r.getBytes(1);
                assertThat(r.wasNull())
                        .as("no identities recorded, carried by the read rather than by a sentinel")
                        .isTrue();
            }
        }
    }

    @Nested
    @DisplayName("The upgrade path onto the ledger itself")
    class OntoTheLedger {

        @Test
        @DisplayName("a database migrated before the ledger existed re-applies every migration safely")
        void preLedgerDatabaseUpgrades() throws SQLException {
            // The real-world path onto ADR-073, and the one that makes the migrations' own
            // idempotency claim executable. A database migrated before the ledger has no
            // exeris_schema_history, so the runner sees nothing recorded and applies the whole set
            // again — over tables that already exist and a row that is already there. Only the
            // IF NOT EXISTS guards make that survivable, and nothing tested them against the real
            // files until now.
            DataSource ds = databaseAtV07();

            upgrade(ds);

            assertThat(ledgerVersions(ds))
                    .as("every shipped migration recorded — order is not asserted because the "
                            + "ledger's version column is TEXT, so a SQL sort gives 0.10.0 before "
                            + "0.5.0. Apply order is the runner's job and has its own test; what "
                            + "this one is about is that nothing was skipped")
                    .containsExactlyInAnyOrder("0.5.0", "0.7.0", "0.10.0", "0.11.0", "0.11.1", "0.11.2");
            assertThat(rowCount(ds, SAGA_TABLE))
                    .as("re-applying over an existing row must not duplicate or drop it")
                    .isEqualTo(1);
        }

        @Test
        @DisplayName("a second upgrade is a no-op, and the parked saga is untouched by it")
        void secondUpgradeChangesNothing() throws SQLException {
            DataSource ds = databaseAtV07();
            upgrade(ds);
            List<String> afterFirst = ledgerVersions(ds);

            upgrade(ds);

            assertThat(ledgerVersions(ds))
                    .as("the ledger MUST NOT grow a second row per version")
                    .isEqualTo(afterFirst);
            assertThat(rowCount(ds, SAGA_TABLE)).isEqualTo(1);
            assertThat(intColumn(ds, "definition_version"))
                    .as("and the backfilled value MUST NOT be rewritten by the re-run")
                    .isEqualTo(FlowSnapshot.VERSION_ABSENT);
        }
    }

    // ---------------------------------------------------------------- fixtures

    /**
     * A database holding the saga table as {@code V0.7.0} created it, with one parked saga in it and
     * no ledger — the state a deployment running an older kernel is actually in.
     */
    private static DataSource databaseAtV07() throws SQLException {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:upgrade_" + System.nanoTime()
                + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
        ds.setUser("sa");
        ds.setPassword("");

        try (Connection c = ds.getConnection(); Statement s = c.createStatement()) {
            s.execute(V07_SAGA_STATE);
        }
        try (Connection c = ds.getConnection();
             PreparedStatement p = c.prepareStatement(
                     "INSERT INTO " + SAGA_TABLE + " (instance_id_most, instance_id_least, "
                             + "definition_name, current_step, state, last_update, "
                             + "compensation_stack, stack_pointer, schema_version) "
                             + "VALUES (?, ?, ?, ?, ?, CURRENT_TIMESTAMP, ?, ?, ?)")) {
            p.setLong(1, ID_MOST);
            p.setLong(2, ID_LEAST);
            p.setString(3, DEFINITION);
            p.setInt(4, 3);
            p.setString(5, "PARKED");
            // Two live entries, packed big-endian as the codec writes them.
            p.setBytes(6, new byte[] {0, 0, 0, 0, 0, 0, 0, 1});
            p.setInt(7, 2);
            p.setLong(8, 1L);
            p.executeUpdate();
        }
        return ds;
    }

    private static void upgrade(DataSource dataSource) {
        CommunityPersistenceMigrationRunner.runIfEnabled(
                true, dataSource, CommunityPersistenceEngine.MIGRATION_RESOURCES,
                PROVIDER, "jdbc:h2:mem:upgrade");
    }

    private static int intColumn(DataSource dataSource, String column) throws SQLException {
        try (Connection c = dataSource.getConnection();
             Statement s = c.createStatement();
             ResultSet r = s.executeQuery("SELECT " + column + " FROM " + SAGA_TABLE)) {
            r.next();
            return r.getInt(1);
        }
    }

    private static int rowCount(DataSource dataSource, String table) throws SQLException {
        try (Connection c = dataSource.getConnection();
             Statement s = c.createStatement();
             ResultSet r = s.executeQuery("SELECT COUNT(*) FROM " + table)) {
            r.next();
            return r.getInt(1);
        }
    }

    private static List<String> ledgerVersions(DataSource dataSource) throws SQLException {
        try (Connection c = dataSource.getConnection();
             Statement s = c.createStatement();
             ResultSet r = s.executeQuery(
                     "SELECT version FROM " + SchemaHistoryLedger.TABLE)) {
            List<String> out = new ArrayList<>();
            while (r.next()) {
                out.add(r.getString(1).toLowerCase(Locale.ROOT));
            }
            return out;
        }
    }
}
