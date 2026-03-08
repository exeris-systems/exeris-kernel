/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.spi.persistence;

import java.util.Locale;

/**
 * SPI: Database dialect capability declaration.
 *
 * <h2>Intent</h2>
 * <p>Each {@link PersistenceProvider} optionally exposes a {@code DatabaseDialect}
 * so the Core layer can route correctly for multi-database deployments and enable
 * or warn about dialect-specific features (e.g., COPY protocol, RLS, binary wire).
 *
 * <h2>The Wall (SPI Compliance)</h2>
 * <p>This interface MUST remain free of any driver-specific class references.
 * The {@code dialectId()} string (e.g., {@code "postgresql"}) is the only concrete
 * coupling permitted.
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * // In PersistenceProvider implementation:
 * public DatabaseDialect dialect(PersistenceConfig config) {
 *     return DatabaseDialect.POSTGRESQL;
 * }
 * }</pre>
 *
 * <h2>Valhalla Readiness</h2>
 * <p>Implementations SHOULD be stateless constants (e.g., enum singletons or
 * pre-built records) to allow JIT constant-folding on dialect checks.
 *
 * @see PersistenceProvider
 * @see PersistenceEngineCapabilities
 * @since 0.5.0
 */
public interface DatabaseDialect {

    // =========================================================================
    // Built-in pre-allocated constants — zero allocation at query time
    // =========================================================================

    /**
     * PostgreSQL dialect constant — covers both Community JDBC and Enterprise native.
     */
    DatabaseDialect POSTGRESQL = new ImmutableDatabaseDialect(
            "postgresql", true, true, true, true
    );

    /**
     * MySQL dialect constant — Community JDBC path only.
     */
    DatabaseDialect MYSQL = new ImmutableDatabaseDialect(
            "mysql", true, false, false, false
    );

    /**
     * SQLite dialect constant — single-file embedded store, no RLS, no COPY.
     */
    DatabaseDialect SQLITE = new ImmutableDatabaseDialect(
            "sqlite", false, false, false, false
    );

    /**
     * Generic JDBC catch-all for unknown dialects (Oracle, MSSQL, etc.).
     */
    DatabaseDialect GENERIC_JDBC = new ImmutableDatabaseDialect(
            "jdbc-generic", true, false, false, false
    );

    // =========================================================================
    // Contract methods
    // =========================================================================

    /**
     * Stable identifier for this dialect (e.g., {@code "postgresql"}, {@code "mysql"},
     * {@code "sqlite"}, {@code "mongodb"}).
     *
     * <p>Used in configuration routing, JFR bootstrap events, and diagnostic output.
     *
     * @return non-null, non-blank dialect identifier
     */
    String dialectId();

    /**
     * Whether this dialect fully supports ACID transactions
     * ({@code BEGIN} / {@code COMMIT} / {@code ROLLBACK}).
     *
     * @return {@code true} if ACID transactions are supported
     */
    boolean supportsTransactions();

    /**
     * Whether this dialect communicates over a binary wire protocol
     * (as opposed to text-based SQL transport).
     *
     * <p>Enterprise PostgreSQL uses the PG wire v3.0 binary protocol.
     * Community JDBC uses a text-level abstraction via the JDBC driver.
     *
     * @return {@code true} if binary wire protocol is available
     */
    boolean supportsBinaryProtocol();

    /**
     * Whether this dialect supports Row-Level Security (RLS) as a
     * session-variable-based isolation mechanism.
     *
     * <p>PostgreSQL supports RLS via {@code SET LOCAL exeris.tenant_id = ?}.
     * MySQL and SQLite do not.
     *
     * @return {@code true} if server-side RLS is available
     */
    boolean supportsRls();

    /**
     * Whether this dialect supports the {@code COPY} bulk-insert protocol
     * (e.g., PostgreSQL {@code COPY table FROM STDIN BINARY}).
     *
     * <p>If {@code true}, the {@link BulkInserter} SPI contract is honoured by
     * the Enterprise provider for this dialect.
     *
     * @return {@code true} if COPY bulk-insert is available
     */
    boolean supportsCopyProtocol();

    /**
     * Autodetects the most appropriate dialect from a JDBC-style connection URL.
     *
     * <p>Prefix matching rules:
     * <ul>
     *   <li>{@code postgresql://} or {@code jdbc:postgresql} → {@link #POSTGRESQL}</li>
     *   <li>{@code mysql://} or {@code jdbc:mysql} → {@link #MYSQL}</li>
     *   <li>{@code sqlite:} or {@code jdbc:sqlite} → {@link #SQLITE}</li>
     *   <li>anything else → {@link #GENERIC_JDBC}</li>
     * </ul>
     *
     * @param connectionUrl the JDBC or driver-native connection URL; never {@code null}
     * @return the detected dialect constant; never {@code null}
     */
    static DatabaseDialect fromUrl(String connectionUrl) {
        if (connectionUrl == null) {
            return GENERIC_JDBC;
        }
        String lower = connectionUrl.toLowerCase(Locale.ROOT);
        if (lower.startsWith("postgresql://") || lower.startsWith("jdbc:postgresql")) {
            return POSTGRESQL;
        }
        if (lower.startsWith("mysql://") || lower.startsWith("jdbc:mysql")) {
            return MYSQL;
        }
        if (lower.startsWith("sqlite:") || lower.startsWith("jdbc:sqlite")) {
            return SQLITE;
        }
        return GENERIC_JDBC;
    }

    // =========================================================================
    // Private impl — Valhalla-ready record (no identity ops)
    // =========================================================================

    /**
     * Immutable dialect descriptor.
     * Valhalla-ready: no synchronized, no ==, no identityHashCode.
     * C2 JIT will scalarise instances used as constants.
     */
    record ImmutableDatabaseDialect(
            String dialectId,
            boolean supportsTransactions,
            boolean supportsBinaryProtocol,
            boolean supportsRls,
            boolean supportsCopyProtocol
    ) implements DatabaseDialect {

        public ImmutableDatabaseDialect {
            if (dialectId == null || dialectId.isBlank()) {
                throw new IllegalArgumentException("dialectId must not be blank");
            }
        }

        @Override
        public String toString() {
            return "DatabaseDialect[" + dialectId + "]";
        }
    }
}
