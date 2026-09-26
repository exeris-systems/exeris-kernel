/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.core.persistence;

import eu.exeris.kernel.spi.exceptions.persistence.PersistenceProviderException;

/**
 * Core: Maps raw PostgreSQL {@code SQLSTATE} codes to Exeris platform error codes.
 *
 * <h2>Responsibility (The Brain)</h2>
 * <p>Neither Community (JDBC SQLException) nor Enterprise (wire protocol error frame)
 * should format user-visible error messages. That is this class's job.
 * All raw SQLState strings produced by drivers flow through
 * {@link #translate(String, String, Throwable)} before reaching the application layer.
 *
 * <h2>SQLSTATE Classes (PostgreSQL)</h2>
 * <ul>
 *   <li>{@code 08} — Connection exceptions</li>
 *   <li>{@code 23} — Integrity constraint violations (most common in business logic)</li>
 *   <li>{@code 25} — Invalid transaction state</li>
 *   <li>{@code 40} — Transaction rollback (serialization failure, deadlock)</li>
 *   <li>{@code 42} — Syntax error / access rule violation</li>
 *   <li>{@code 53} — Insufficient resources</li>
 *   <li>{@code 57} — Operator intervention (admin cancel, shutdown)</li>
 *   <li>{@code 58} — System error (I/O, file not found)</li>
 * </ul>
 *
 * <h2>Performance Contract</h2>
 * <p>All lookups are O(1) — switch on first two chars (SQLSTATE class).
 * No {@code HashMap}, no regex, no reflection.
 *
 * <h2>The Wall (Open-Core)</h2>
 * <p>Imports only {@code exeris-kernel-spi}. Completely ignorant of JDBC, HikariCP,
 * pgjdbc, or the PG wire protocol. Operates on plain {@code String} SQLState codes.
 *
 * @since 0.5
 */
@SuppressWarnings("PMD.CyclomaticComplexity")
public final class PersistenceErrorTranslator {

    // -------------------------------------------------------------------------
    // Specific SQLSTATE values — most frequent in business logic
    // -------------------------------------------------------------------------
    private static final String UNIQUE_VIOLATION      = "23505";
    private static final String FK_VIOLATION          = "23503";
    private static final String NOT_NULL_VIOLATION    = "23502";
    private static final String CHECK_VIOLATION       = "23514";
    private static final String EXCLUSION_VIOLATION   = "23P01";
    private static final String SERIALIZATION_FAILURE = "40001";
    private static final String DEADLOCK_DETECTED     = "40P01";
    private static final String QUERY_CANCELLED       = "57014";
    private static final String UNDEFINED_TABLE       = "42P01";
    private static final String UNDEFINED_COLUMN      = "42703";
    private static final String DISK_FULL             = "53100";
    private static final String OUT_OF_MEMORY         = "53200";
    private static final String TOO_MANY_CONNECTIONS  = "53300";
    private static final String CONN_FAILURE          = "08006";
    private static final String CONN_REFUSED          = "08001";
    private static final String INVALID_PASSWORD      = "28P01";

    // -------------------------------------------------------------------------
    // SQLSTATE class prefixes (first two characters)
    // -------------------------------------------------------------------------
    private static final String CLASS_CONNECTION   = "08";
    private static final String CLASS_CONSTRAINT   = "23";
    private static final String CLASS_AUTH         = "28";
    private static final String CLASS_TX_STATE     = "25";
    private static final String CLASS_TX_ROLLBACK  = "40";
    private static final String CLASS_SYNTAX       = "42";
    private static final String CLASS_RESOURCES    = "53";
    private static final String CLASS_OPERATOR     = "57";
    private static final String CLASS_SYSTEM_ERROR = "58";
    private static final String CLASS_PLPGSQL      = "P0";


    private PersistenceErrorTranslator() {
        // utility — no instances
    }

    /**
     * Translates a raw PostgreSQL {@code SQLSTATE} code into a
     * {@link PersistenceProviderException} with the appropriate Exeris error code.
     *
     * @param sqlState   5-character PostgreSQL SQLSTATE (e.g. {@code "23505"}); {@code null} or a
     *                   string shorter than two characters is mapped generically rather than
     *                   rejected
     * @param detail     server error detail / message (stored in {@code rawArgs})
     * @param cause      original exception (may be {@code null} in Enterprise native path)
     * @return a {@link PersistenceProviderException} carrying
     *         {@value eu.exeris.kernel.spi.exceptions.KernelErrorCodes#EX_PERS_5004} for any
     *         SQLSTATE in the authentication class ({@code 28}), and
     *         {@value eu.exeris.kernel.spi.exceptions.KernelErrorCodes#EX_PERS_5003} for every
     *         other recognized or unrecognized code, ready to propagate to the caller
     */
    public static PersistenceProviderException translate(String sqlState,
                                                          String detail,
                                                          Throwable cause) {
        if (sqlState == null || sqlState.length() < 2) {
            return PersistenceProviderException.queryFailed("[unknown]", detail, cause);
        }
        // Specific codes take precedence over class-level mapping
        PersistenceProviderException specific = translateSpecific(sqlState, detail, cause);
        if (specific != null) {
            return specific;
        }
        return translateByClass(sqlState, detail, cause);
    }

    /**
     * Returns {@code true} if the given {@code SQLSTATE} represents a transient error
     * that the caller SHOULD retry.
     *
     * <p>Any code in class {@code 40} (Transaction Rollback) is considered retryable,
     * including {@code 40001} (serialization failure), {@code 40P01} (deadlock detected),
     * {@code 40002} (transaction integrity constraint violation), and
     * {@code 40003} (statement completion unknown). This is consistent with the
     * {@code [RETRYABLE]} hint emitted by {@link #translate} for the same class.
     *
     * @param sqlState 5-character PostgreSQL SQLSTATE
     * @return {@code true} if the operation is safely retryable
     */
    public static boolean isRetryable(String sqlState) {
        return sqlState != null && sqlState.startsWith(CLASS_TX_ROLLBACK);
    }

    /**
     * Returns {@code true} if the given {@code SQLSTATE} is a constraint violation (class 23).
     *
     * @param sqlState 5-character PostgreSQL SQLSTATE
     * @return {@code true} if {@code sqlState} is in constraint-violation class {@code 23}
     */
    public static boolean isConstraintViolation(String sqlState) {
        return sqlState != null && sqlState.startsWith(CLASS_CONSTRAINT);
    }

    // =========================================================================
    // Private helpers — split for CyclomaticComplexity compliance
    // =========================================================================

    private static PersistenceProviderException translateSpecific(String sqlState,
                                                                    String detail,
                                                                    Throwable cause) {
        return switch (sqlState) {
            case UNIQUE_VIOLATION     -> constraint(sqlState, detail, cause, "unique constraint violated");
            case FK_VIOLATION         -> constraint(sqlState, detail, cause, "foreign key constraint violated");
            case NOT_NULL_VIOLATION   -> constraint(sqlState, detail, cause, "not-null constraint violated");
            case CHECK_VIOLATION      -> constraint(sqlState, detail, cause, "check constraint violated");
            case EXCLUSION_VIOLATION  -> constraint(sqlState, detail, cause, "exclusion constraint violated");
            case SERIALIZATION_FAILURE -> retryable(sqlState, detail, cause,
                    "serialization failure — transaction must be retried");
            case DEADLOCK_DETECTED    -> retryable(sqlState, detail, cause,
                    "deadlock detected — transaction must be retried");
            case QUERY_CANCELLED      -> PersistenceProviderException.queryFailed(
                    sqlState, "query cancelled by operator or statement_timeout", cause);
            case UNDEFINED_TABLE      -> PersistenceProviderException.queryFailed(
                    sqlState, "undefined table — check schema migrations: " + detail, cause);
            case UNDEFINED_COLUMN     -> PersistenceProviderException.queryFailed(
                    sqlState, "undefined column — check schema migrations: " + detail, cause);
            case TOO_MANY_CONNECTIONS  -> PersistenceProviderException.queryFailed(
                    sqlState, "too many connections — server pool exhausted: " + detail, cause);
            case DISK_FULL            -> PersistenceProviderException.queryFailed(
                    sqlState, "disk full — database I/O failure: " + detail, cause);
            case OUT_OF_MEMORY        -> PersistenceProviderException.queryFailed(
                    sqlState, "out of memory on database server: " + detail, cause);
            case CONN_FAILURE, CONN_REFUSED -> PersistenceProviderException.queryFailed(
                    sqlState, "connection failure: " + detail, cause);
            case INVALID_PASSWORD      -> PersistenceProviderException.authFailed(sqlState, detail, cause);
            default -> null; // fall through to class-level mapping
        };
    }

    private static PersistenceProviderException translateByClass(String sqlState,
                                                                   String detail,
                                                                   Throwable cause) {
        String clazz = sqlState.substring(0, 2);
        return switch (clazz) {
            case CLASS_CONNECTION   -> PersistenceProviderException.queryFailed(
                    sqlState, "connection failure: " + detail, cause);
            case CLASS_CONSTRAINT   -> constraint(sqlState, detail, cause, "constraint violated");
            case CLASS_AUTH         -> PersistenceProviderException.authFailed(sqlState, detail, cause);
            case CLASS_TX_STATE     -> PersistenceProviderException.queryFailed(
                    sqlState, "invalid transaction state: " + detail, cause);
            case CLASS_TX_ROLLBACK  -> retryable(sqlState, detail, cause,
                    "transaction aborted — retry required");
            case CLASS_SYNTAX       -> PersistenceProviderException.queryFailed(
                    sqlState, "SQL syntax error or privilege violation: " + detail, cause);
            case CLASS_RESOURCES    -> PersistenceProviderException.queryFailed(
                    sqlState, "insufficient server resources: " + detail, cause);
            case CLASS_OPERATOR     -> PersistenceProviderException.queryFailed(
                    sqlState, "operator intervention (cancel/shutdown): " + detail, cause);
            case CLASS_SYSTEM_ERROR -> PersistenceProviderException.queryFailed(
                    sqlState, "system I/O error: " + detail, cause);
            case CLASS_PLPGSQL      -> PersistenceProviderException.queryFailed(
                    sqlState, "PL/pgSQL error: " + detail, cause);
            default                 -> PersistenceProviderException.queryFailed(
                    sqlState, detail, cause);
        };
    }

    private static PersistenceProviderException constraint(String sqlState, String detail,
                                                            Throwable cause, String hint) {
        return PersistenceProviderException.queryFailed(sqlState, hint + ": " + detail, cause);
    }

    private static PersistenceProviderException retryable(String sqlState, String detail,
                                                           Throwable cause, String hint) {
        return PersistenceProviderException.queryFailed(
                sqlState, "[RETRYABLE] " + hint + ": " + detail, cause);
    }
}

