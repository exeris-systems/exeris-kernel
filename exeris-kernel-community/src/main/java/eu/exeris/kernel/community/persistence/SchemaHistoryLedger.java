/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.persistence;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;

/**
 * The record of which migrations this database has already had applied (ADR-073).
 *
 * <p>Before this existed, apply-once was not a property of the runner but of how each migration
 * happened to be written: every shipped script guarded its DDL with {@code IF NOT EXISTS}, so
 * re-running the whole set on every boot was a no-op. That holds only until the first migration
 * that cannot be written that way — a data backfill, a {@code DROP COLUMN}, a constraint tightening
 * — and the backfill case fails <em>silently</em>, because a re-applied {@code UPDATE} leaves a
 * healthy boot and wrong data.
 *
 * <p>The ledger table is created with {@code IF NOT EXISTS} and is deliberately not recorded in
 * itself: it is the one piece of schema whose creation has to stay idempotent, because there is
 * nothing to record it in.
 *
 * @since 0.12.0
 */
final class SchemaHistoryLedger {

    /* default */ static final String TABLE = "exeris_schema_history";

    private static final String CREATE_SQL =
            "CREATE TABLE IF NOT EXISTS " + TABLE + " ("
                    + "version VARCHAR(128) NOT NULL PRIMARY KEY, "
                    + "script VARCHAR(512) NOT NULL, "
                    + "checksum VARCHAR(64) NOT NULL, "
                    + "applied_at TIMESTAMP NOT NULL)";

    private static final String SELECT_SQL = "SELECT version, checksum FROM " + TABLE;

    private static final String INSERT_SQL =
            "INSERT INTO " + TABLE + " (version, script, checksum, applied_at) VALUES (?, ?, ?, ?)";

    private static final char[] HEX = "0123456789abcdef".toCharArray();

    private SchemaHistoryLedger() {
        // utility — no instances
    }

    /** Creates the ledger table if this database has never carried one. */
    /* default */ static void ensureTable(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute(CREATE_SQL);
        }
    }

    /**
     * Reads the whole ledger.
     *
     * <p>One query rather than a lookup per migration: the set is small, it is read once at boot,
     * and a single read cannot observe the table changing underneath it halfway through.
     *
     * @return version → checksum for every migration already applied
     */
    /* default */ static Map<String, String> load(Connection connection) throws SQLException {
        Map<String, String> applied = new HashMap<>();
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery(SELECT_SQL)) {
            while (rows.next()) {
                applied.put(rows.getString(1), rows.getString(2));
            }
        }
        return applied;
    }

    /**
     * Records one applied migration. The caller MUST run this in the same transaction as the
     * migration's own statements — that is what stops the ledger claiming something the database
     * does not have, and vice versa.
     *
     * @param appliedAt wall-clock instant of application, supplied by the caller so the value is
     *                  not read from a clock buried in here. Stored as UTC: a ledger read across
     *                  timezones must not depend on where the reader is.
     */
    /* default */ static void record(Connection connection,
                                     String version,
                                     String script,
                                     String checksum,
                                     Instant appliedAt) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(INSERT_SQL)) {
            statement.setString(1, version);
            statement.setString(2, script);
            statement.setString(3, checksum);
            statement.setObject(4, LocalDateTime.ofInstant(appliedAt, ZoneOffset.UTC));
            statement.executeUpdate();
        }
    }

    /**
     * SHA-256 of the migration source, hex-encoded, with {@code \r\n} folded to {@code \n} first.
     *
     * <p>The normalisation is the only one applied and it exists for one reason: without it, a
     * checkout on Windows changes every checksum and refuses every boot — a refusal with nothing
     * wrong, which teaches operators to distrust the mechanism. Whitespace and comments stay
     * <em>inside</em> the checksum on purpose: an edit to a migration is an edit whether or not it
     * changed the parse.
     */
    /* default */ static String checksumOf(String migrationSql) {
        String normalised = migrationSql.replace("\r\n", "\n");
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException cause) {
            // SHA-256 is mandated by the platform; absence is not a condition to degrade around.
            throw new IllegalStateException("SHA-256 unavailable", cause);
        }
        byte[] hash = digest.digest(normalised.getBytes(StandardCharsets.UTF_8));
        char[] hex = new char[hash.length * 2];
        for (int i = 0; i < hash.length; i++) {
            int octet = hash[i] & 0xFF;
            hex[i * 2] = HEX[octet >>> 4];
            hex[i * 2 + 1] = HEX[octet & 0x0F];
        }
        return new String(hex);
    }
}
