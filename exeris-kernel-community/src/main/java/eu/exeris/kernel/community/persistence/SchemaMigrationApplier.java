/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.community.persistence;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;

/**
 * Decides which migrations still need applying and applies them, one transaction each (ADR-073).
 *
 * <p>Split out of {@link CommunityPersistenceMigrationRunner} rather than added to it. The runner's
 * job is reading, ordering and splitting a script; deciding whether a script should run at all is a
 * different question and now has a different owner. It also keeps the runner off a PMD
 * {@code TooManyMethods} ceiling — a limit this repository has twice met by inlining a helper to fit,
 * which trades a real structure for a passing gate.
 *
 * @since 0.12.0
 */
final class SchemaMigrationApplier {

    private SchemaMigrationApplier() {
        // utility — no instances
    }

    /**
     * Applies every migration the ledger does not already carry, one transaction each (ADR-073 §4).
     *
     * <p>Per-migration rather than one transaction for the set: with a ledger there is finally
     * something to commit alongside each script, and the previous all-or-nothing shape meant one
     * failing migration discarded the work of every migration before it, on every boot, forever.
     */
    /* default */ static void applyPending(Connection connection,
                                           List<String> resources) throws SQLException {
        Map<String, String> applied = SchemaHistoryLedger.load(connection);
        for (String resource : resources) {
            String migrationSql = CommunityPersistenceMigrationRunner.readMigrationResource(resource);
            String version = versionOf(resource);
            String checksum = SchemaHistoryLedger.checksumOf(migrationSql);
            String recorded = applied.get(version);
            if (recorded != null) {
                refuseIfChanged(version, resource, checksum, recorded);
                continue;
            }
            applyOne(connection, resource, version, checksum, migrationSql);
        }
    }

    /**
     * Fail-closed on drift (ADR-073 §2). The script that produced this database is not the script on
     * the classpath, so the schema does not match the code about to run against it. Warning and
     * continuing is what produces a healthy-looking boot on a drifted database, which is the state
     * this ledger exists to make visible.
     */
    private static void refuseIfChanged(String version, String resource, String checksum, String recorded) {
        if (!recorded.equals(checksum)) {
            throw new IllegalStateException(
                    "Migration " + version + " (" + resource + ") was applied with checksum "
                            + recorded + " but the file on the classpath now hashes to " + checksum
                            + ". The database no longer matches the code. Restore the migration, or "
                            + "remove its row from " + SchemaHistoryLedger.TABLE + " if the change is "
                            + "known to be already applied.");
        }
    }

    private static void applyOne(Connection connection,
                                 String resource,
                                 String version,
                                 String checksum,
                                 String migrationSql) throws SQLException {
        connection.setAutoCommit(false);
        boolean committed = false;
        try {
            CommunityPersistenceMigrationRunner.executeStatements(connection, migrationSql);
            SchemaHistoryLedger.record(
                    connection, version, resource, checksum, Instant.now());
            connection.commit();
            committed = true;
        } finally {
            restoreAutoCommit(connection, committed);
        }
    }

    /**
     * Ledger key for a migration. The parsed {@code <major>.<minor>.<patch>} when the name carries
     * one; otherwise the resource path, which keeps unversioned resources addressable rather than
     * collapsing them all onto one key.
     */
    private static String versionOf(String resourcePath) {
        Matcher matcher = CommunityPersistenceMigrationRunner.VERSION_PATTERN.matcher(resourcePath);
        if (!matcher.find()) {
            return resourcePath;
        }
        return matcher.group(1) + '.' + matcher.group(2) + '.' + matcher.group(3);
    }

    private static void restoreAutoCommit(Connection connection, boolean committed) throws SQLException {
        try {
            if (!committed) {
                connection.rollback();
            }
        } finally {
            connection.setAutoCommit(true);
        }
    }
}
