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
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * ADR-073 — the schema-history ledger.
 *
 * <p>Every assertion here is written against a migration that is <em>not</em> idempotent, because a
 * migration guarded by {@code IF NOT EXISTS} cannot fail an apply-once test and therefore cannot
 * prove one. That is exactly the property the ledger exists to stop depending on, so a test that
 * relied on it would be testing nothing.
 *
 * @since 0.12.0
 */
@DisplayName("Community: schema-history ledger (ADR-073)")
class CommunitySchemaHistoryLedgerTest {

    private static final String PROVIDER = "postgres-community";

    @Test
    @DisplayName("a non-idempotent migration runs once across two boots")
    void nonIdempotentMigrationRunsOnceAcrossTwoBoots() throws Exception {
        DataSource ds = freshDatabase("ledger_applyonce");

        boot(ds, List.of("db/ledgertest/V1.0.0__counter.sql"));
        boot(ds, List.of("db/ledgertest/V1.0.0__counter.sql"));

        // The INSERT is unguarded. Before the ledger, the second boot re-ran the whole set and this
        // read 2 — a data backfill silently applied twice, with a healthy-looking boot on top.
        assertThat(rowCount(ds, "ledger_probe"))
                .as("the unguarded INSERT MUST have been applied exactly once")
                .isEqualTo(1);
        assertThat(ledgerVersions(ds))
                .as("the applied migration MUST be recorded, keyed by version")
                .containsExactly("1.0.0");
    }

    @Test
    @DisplayName("a migration whose bytes changed after it was applied refuses the boot")
    void changedMigrationRefusesTheBoot() throws Exception {
        DataSource ds = freshDatabase("ledger_drift");

        boot(ds, List.of("db/ledgertest/V2.0.0__drift_a.sql"));

        // Same version key, different bytes — what an edited migration looks like to the ledger.
        assertThatThrownBy(() -> boot(ds, List.of("db/ledgertest/V2.0.0__drift_b.sql")))
                .as("a database that no longer matches its code MUST NOT boot")
                .isInstanceOf(PersistenceProviderException.class)
                .hasRootCauseInstanceOf(IllegalStateException.class)
                .rootCause()
                .hasMessageContaining("2.0.0")
                .hasMessageContaining(SchemaHistoryLedger.TABLE);
    }

    @Test
    @DisplayName("an unchanged migration is skipped rather than refused")
    void unchangedMigrationIsSkipped() throws Exception {
        DataSource ds = freshDatabase("ledger_stable");

        boot(ds, List.of("db/ledgertest/V2.0.0__drift_a.sql"));
        boot(ds, List.of("db/ledgertest/V2.0.0__drift_a.sql"));

        // The negative control for the test above: refusal has to discriminate, not just fire.
        assertThat(ledgerVersions(ds)).containsExactly("2.0.0");
    }

    @Test
    @DisplayName("a failing migration keeps the ones before it, in the schema and in the ledger")
    void failingMigrationKeepsEarlierOnes() throws Exception {
        DataSource ds = freshDatabase("ledger_partial");

        assertThatThrownBy(() -> boot(ds, List.of(
                "db/ledgertest/V3.0.0__first.sql",
                "db/ledgertest/V3.1.0__broken.sql")))
                .isInstanceOf(PersistenceProviderException.class);

        // One transaction per migration (ADR-073 §4). Under the previous single-transaction shape
        // the failure discarded V3.0.0 too, so every boot replayed everything and every boot failed
        // in the same place with nothing kept.
        assertThat(tableExists(ds, "partial_first"))
                .as("the migration that succeeded MUST stay applied")
                .isTrue();
        assertThat(ledgerVersions(ds))
                .as("the ledger MUST record exactly what the database has — no more, no less")
                .containsExactly("3.0.0");
    }

    @Test
    @DisplayName("checksums fold CRLF, so a Windows checkout does not refuse every boot")
    void checksumIgnoresLineEndingStyle() {
        String lf = "CREATE TABLE t (id INT);\nINSERT INTO t VALUES (1);\n";
        String crlf = lf.replace("\n", "\r\n");

        assertThat(SchemaHistoryLedger.checksumOf(crlf))
                .as("a checkout on Windows must not invalidate every recorded checksum")
                .isEqualTo(SchemaHistoryLedger.checksumOf(lf));
        assertThat(SchemaHistoryLedger.checksumOf(lf + "-- edited\n"))
                .as("but a real edit MUST change the checksum, or the normalisation ate the signal")
                .isNotEqualTo(SchemaHistoryLedger.checksumOf(lf));
    }

    // ---------------------------------------------------------------- helpers

    private static void boot(DataSource dataSource, List<String> resources) {
        CommunityPersistenceMigrationRunner.runIfEnabled(
                true, dataSource, resources, PROVIDER, "jdbc:h2:mem:test");
    }

    private static DataSource freshDatabase(String name) {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:" + name + '_' + System.nanoTime()
                + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
        ds.setUser("sa");
        ds.setPassword("");
        return ds;
    }

    private static int rowCount(DataSource dataSource, String table) throws SQLException {
        try (Connection c = dataSource.getConnection();
             Statement s = c.createStatement();
             ResultSet r = s.executeQuery("SELECT COUNT(*) FROM " + table)) {
            r.next();
            return r.getInt(1);
        }
    }

    private static boolean tableExists(DataSource dataSource, String table) throws SQLException {
        try (Connection c = dataSource.getConnection();
             ResultSet r = c.getMetaData().getTables(null, null, table.toUpperCase(java.util.Locale.ROOT), null)) {
            return r.next();
        }
    }

    private static List<String> ledgerVersions(DataSource dataSource) throws SQLException {
        try (Connection c = dataSource.getConnection();
             Statement s = c.createStatement();
             ResultSet r = s.executeQuery(
                     "SELECT version FROM " + SchemaHistoryLedger.TABLE + " ORDER BY version")) {
            List<String> out = new java.util.ArrayList<>();
            while (r.next()) {
                out.add(r.getString(1));
            }
            return out;
        }
    }
}
