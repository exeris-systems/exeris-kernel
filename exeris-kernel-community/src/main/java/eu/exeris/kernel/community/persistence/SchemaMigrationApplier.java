/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.community.persistence;

import eu.exeris.kernel.core.persistence.SchemaMigrationRefusedEvent;

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
            // The throw stops the boot; the event is how an operator learns why without parsing a
            // log line. Emitted before the throw so the record exists even if the exception is
            // swallowed or reshaped further up.
            SchemaMigrationRefusedEvent.commitRefusal(new SchemaMigrationRefusedEvent.Payload(
                    version, resource, recorded, checksum));
            throw new IllegalStateException(
                    "Migration " + version + " (" + resource + ") was applied with checksum "
                            + recorded + " but the file on the classpath now hashes to " + checksum
                            + ". The database no longer matches the code. Restore the migration, or "
                            + "remove its row from " + SchemaHistoryLedger.TABLE + " if the change is "
                            + "known to be already applied.");
        }
    }

    // The catch is deliberately broad: anything that escapes a migration must not leave the
    // connection in a transaction, and the cleanup must not become the reported failure.
    @SuppressWarnings("PMD.AvoidCatchingGenericException")
    private static void applyOne(Connection connection,
                                 String resource,
                                 String version,
                                 String checksum,
                                 String migrationSql) throws SQLException {
        connection.setAutoCommit(false);
        try {
            CommunityPersistenceMigrationRunner.executeStatements(connection, migrationSql);
            SchemaHistoryLedger.record(
                    connection, version, resource, checksum, Instant.now());
            connection.commit();
        } catch (SQLException | RuntimeException failure) {
            cleanUpAfter(connection, failure);
            throw failure;
        }
        // Success path only, and it is allowed to throw: a connection that cannot leave the
        // transaction is a real failure with nothing else competing to be reported.
        connection.setAutoCommit(true);
    }

    /**
     * Rolls back and restores auto-commit without ever becoming the reported failure.
     *
     * <p>The previous shape rolled back inside a {@code finally}, so a rollback that itself threw -
     * a dropped connection is the ordinary way - **replaced** the migration error that caused it.
     * The operator then saw the cleanup failure and lost the one fact that mattered. Both cleanup
     * failures attach to the original as suppressed instead.
     */
    private static void cleanUpAfter(Connection connection, Exception primary) {
        try {
            connection.rollback();
        } catch (SQLException rollbackFailure) {
            primary.addSuppressed(rollbackFailure);
        }
        try {
            connection.setAutoCommit(true);
        } catch (SQLException restoreFailure) {
            primary.addSuppressed(restoreFailure);
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

}
