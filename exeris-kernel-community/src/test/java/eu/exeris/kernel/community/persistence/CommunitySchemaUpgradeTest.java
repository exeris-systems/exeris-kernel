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
    /** Two live entries, packed big-endian as the codec writes them. */
    private static final byte[] PARKED_STACK = {0, 0, 0, 0, 0, 0, 0, 1};

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
            Database ds = databaseAtV07();

            upgrade(ds);

            try (Connection c = ds.dataSource().getConnection();
                 Statement s = c.createStatement();
                 ResultSet r = s.executeQuery("SELECT instance_id_most, instance_id_least, "
                         + "definition_name, current_step, state, compensation_stack, "
                         + "stack_pointer, schema_version FROM " + SAGA_TABLE)) {
                assertThat(r.next()).as("the parked saga MUST still be there").isTrue();
                // Every column the row was written with, including the primary key and the packed
                // stack. A representative subset would read as a spot-check while the claim being
                // made is that nothing moved — and an ALTER that rewrote the PK or the BYTEA is
                // exactly the kind of migration this suite exists to catch.
                assertThat(r.getLong("instance_id_most")).isEqualTo(ID_MOST);
                assertThat(r.getLong("instance_id_least")).isEqualTo(ID_LEAST);
                assertThat(r.getString("definition_name")).isEqualTo(DEFINITION);
                assertThat(r.getInt("current_step")).isEqualTo(3);
                assertThat(r.getString("state")).isEqualTo("PARKED");
                assertThat(r.getBytes("compensation_stack")).isEqualTo(PARKED_STACK);
                assertThat(r.getInt("stack_pointer")).isEqualTo(2);
                assertThat(r.getLong("schema_version")).isEqualTo(1L);
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
            Database ds = databaseAtV07();

            upgrade(ds);

            assertThat(intColumn(ds.dataSource(), "definition_version"))
                    .as("a pre-0.11.1 row records no version, and says so")
                    .isEqualTo(FlowSnapshot.VERSION_ABSENT);
        }

        @Test
        @DisplayName("leaves compensation_step_names NULL, which is the opposite choice and correct")
        void identitiesBackfillNull() throws SQLException {
            // V0.11.2 takes the other option from its immediate predecessor on purpose: a BYTEA can
            // represent NULL, so absence is carried by the read rather than by a sentinel. Asserted
            // because "both columns backfill" would pass against either choice, and the two
            // migrations disagreeing is what makes each one deliberate.
            Database ds = databaseAtV07();

            upgrade(ds);

            try (Connection c = ds.dataSource().getConnection();
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
            Database ds = databaseAtV07();

            upgrade(ds);

            assertThat(ledgerVersions(ds.dataSource()))
                    .as("every shipped migration recorded — order is not asserted because the "
                            + "ledger's version column is TEXT, so a SQL sort gives 0.10.0 before "
                            + "0.5.0. Apply order is the runner's job and has its own test; what "
                            + "this one is about is that nothing was skipped")
                    .containsExactlyInAnyOrder("0.5.0", "0.7.0", "0.10.0", "0.11.0", "0.11.1", "0.11.2", "0.13.0");
            assertThat(rowCount(ds.dataSource(), SAGA_TABLE))
                    .as("re-applying over an existing row must not duplicate or drop it")
                    .isEqualTo(1);
        }

        @Test
        @DisplayName("a second upgrade is a no-op, and the parked saga is untouched by it")
        void secondUpgradeChangesNothing() throws SQLException {
            Database ds = databaseAtV07();
            upgrade(ds);
            List<String> afterFirst = ledgerVersions(ds.dataSource());

            upgrade(ds);

            assertThat(ledgerVersions(ds.dataSource()))
                    .as("the ledger MUST NOT grow a second row per version")
                    .isEqualTo(afterFirst);
            assertThat(rowCount(ds.dataSource(), SAGA_TABLE)).isEqualTo(1);
            assertThat(intColumn(ds.dataSource(), "definition_version"))
                    .as("and the backfilled value MUST NOT be rewritten by the re-run")
                    .isEqualTo(FlowSnapshot.VERSION_ABSENT);
        }
    }

    @Nested
    @DisplayName("Outbox and event log rows written before the isolation column (ADR-101)")
    class PublicationRowsBeforeIsolation {

        @Test
        @DisplayName("gain isolation_ordinal on upgrade, and every existing row reads 0 (unscoped)")
        void existingRowsBackfillToUnscoped() throws SQLException {
            Database ds = databaseWithPre013PublicationTables();

            upgrade(ds);

            assertThat(intValue(ds.dataSource(), "SELECT isolation_ordinal FROM exeris_outbox"))
                    .as("an outbox row published before isolation had no scope, which is 0")
                    .isZero();
            assertThat(intValue(ds.dataSource(), "SELECT isolation_ordinal FROM exeris_event_log"))
                    .as("an event-log row written before isolation had no scope, which is 0")
                    .isZero();
        }

        @Test
        @DisplayName("keep the outbox payload and the event-log payload they were written with")
        void payloadsAreUnchanged() throws SQLException {
            Database ds = databaseWithPre013PublicationTables();

            upgrade(ds);

            try (Connection c = ds.dataSource().getConnection();
                 Statement s = c.createStatement();
                 ResultSet r = s.executeQuery("SELECT payload FROM exeris_outbox")) {
                assertThat(r.next()).isTrue();
                assertThat(r.getBytes(1)).isEqualTo(OUTBOX_PAYLOAD);
            }
            try (Connection c = ds.dataSource().getConnection();
                 Statement s = c.createStatement();
                 ResultSet r = s.executeQuery("SELECT payload FROM exeris_event_log")) {
                assertThat(r.next()).isTrue();
                assertThat(r.getBytes(1)).isEqualTo(OUTBOX_PAYLOAD);
            }
        }
    }

    // ---------------------------------------------------------------- fixtures

    private static final byte[] OUTBOX_PAYLOAD = {1, 2, 3};

    /**
     * A database holding the outbox and the event log exactly as {@code V0.5.0} and {@code V0.10.0}
     * created them, with one row in each, and no isolation column.
     */
    private static Database databaseWithPre013PublicationTables() throws SQLException {
        String url = "jdbc:h2:mem:upgrade_pub_" + System.nanoTime()
                + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1";
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL(url);
        ds.setUser("sa");
        ds.setPassword("");

        try (Connection c = ds.getConnection()) {
            CommunityPersistenceMigrationRunner.executeStatements(c,
                    CommunityPersistenceMigrationRunner.readMigrationResource("db/migration/V0.5.0__create_outbox.sql"));
            CommunityPersistenceMigrationRunner.executeStatements(c,
                    CommunityPersistenceMigrationRunner.readMigrationResource("db/migration/V0.10.0__create_event_log.sql"));
        }
        try (Connection c = ds.getConnection();
             PreparedStatement p = c.prepareStatement(
                     "INSERT INTO exeris_outbox (id, aggregate_id, aggregate_type, event_type, payload, occurred_at) "
                             + "VALUES (?, ?, ?, ?, ?, ?)")) {
            p.setObject(1, java.util.UUID.randomUUID());
            p.setString(2, "agg-1");
            p.setString(3, "Order");
            p.setString(4, "OrderPlaced");
            p.setBytes(5, OUTBOX_PAYLOAD);
            p.setLong(6, 1L);
            p.executeUpdate();
        }
        try (Connection c = ds.getConnection();
             PreparedStatement p = c.prepareStatement(
                     "INSERT INTO exeris_event_log (stream_id_high, stream_id_low, committed_sequence, "
                             + "event_id_high, event_id_low, event_type_ordinal, event_flags, occurred_at, payload) "
                             + "VALUES (1, 2, 1, 3, 4, 7, 0, 1, ?)")) {
            p.setBytes(1, OUTBOX_PAYLOAD);
            p.executeUpdate();
        }
        return new Database(ds, url);
    }

    private static int intValue(DataSource dataSource, String sql) throws SQLException {
        try (Connection c = dataSource.getConnection();
             Statement s = c.createStatement();
             ResultSet r = s.executeQuery(sql)) {
            assertThat(r.next()).as("a row MUST exist for %s", sql).isTrue();
            return r.getInt(1);
        }
    }

    /**
     * A database holding the saga table as {@code V0.7.0} created it, with one parked saga in it and
     * no ledger — the state a deployment running an older kernel is actually in.
     */
    private static Database databaseAtV07() throws SQLException {
        String url = "jdbc:h2:mem:upgrade_" + System.nanoTime()
                + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1";
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL(url);
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
            p.setBytes(6, PARKED_STACK);
            p.setInt(7, 2);
            p.setLong(8, 1L);
            p.executeUpdate();
        }
        return new Database(ds, url);
    }

    /**
     * The database and the URL it was opened on.
     *
     * <p>The URL is carried rather than re-derived because the runner embeds it in the exception a
     * bootstrap failure throws. A hardcoded stand-in would point a failing case at a database nobody
     * opened — harmless until the day it is the only clue.
     */
    private record Database(DataSource dataSource, String url) {
    }

    private static void upgrade(Database database) {
        CommunityPersistenceMigrationRunner.runIfEnabled(
                true, database.dataSource(), CommunityPersistenceEngine.MIGRATION_RESOURCES,
                PROVIDER, database.url());
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
                out.add(r.getString(1));
            }
            return out;
        }
    }
}
