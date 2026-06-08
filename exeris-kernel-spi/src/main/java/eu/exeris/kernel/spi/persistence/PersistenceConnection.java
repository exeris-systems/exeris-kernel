/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.spi.persistence;

import eu.exeris.kernel.spi.exceptions.persistence.PersistenceProviderException;

/**
 * SPI: A single database connection with query execution capabilities.
 *
 * <h2>Zero-Copy Contract (Enterprise)</h2>
 * <p>In the Enterprise tier, {@link #executeQuery(String)} returns a {@link QueryResult}
 * whose {@link QueryResult#row()} provides a {@link RowCursor} reading directly from
 * off-heap {@code LoanedBuffer} — zero heap allocation in the iteration loop.
 *
 * <h2>Prepared Statement Path (ADR-010 L0 Fix)</h2>
 * <p>For parameterised queries, use {@link #prepare(String)} to obtain a
 * {@link PersistenceStatement} with typed binders ({@code bindInt}, {@code bindLong},
 * etc.) that eliminate autoboxing and {@code Object[]} allocation entirely.
 * The legacy {@code executeQuery(sql, Object...)} overloads have been removed.
 *
 * <h2>Transaction Control</h2>
 * <p>Connections default to auto-commit OFF. Use {@link #beginTransaction()},
 * {@link #beginTransaction(TransactionIsolation, boolean)},
 * {@link #commit()}, and {@link #rollback()} for explicit transaction boundaries.
 *
 * <h2>Lifecycle</h2>
 * <pre>
 *  engine.openConnection()
 *      → conn.beginTransaction(SERIALIZABLE, false)
 *      → stmt = conn.prepare(sql)
 *      → stmt.bindInt(0, value)
 *      → rs = stmt.executeQuery()
 *      → iterate QueryResult
 *      → stmt.close()
 *      → conn.commit()
 *      → conn.close() → returns to pool
 * </pre>
 *
 * <h2>Thread Safety</h2>
 * <p>NOT thread-safe. Each virtual thread MUST own its own connection.
 *
 * @see PersistenceEngine
 * @see PersistenceStatement
 * @see QueryResult
 * @since 0.5.0
 */
@SuppressWarnings("PMD.TooManyMethods") // SPI contract surface — method count is intrinsic
public interface PersistenceConnection extends AutoCloseable {

    // =========================================================================
    // Prepared Statement Factory (Zero-Allocation Path)
    // =========================================================================

    /**
     * Prepares a statement for execution with typed parameter binding.
     *
     * <p>This is the <b>preferred</b> query execution path — it eliminates the
     * autoboxing and {@code Object[]} allocation overhead of the legacy
     * {@code executeQuery(sql, params...)} pattern.
     *
     * <p><b>Community:</b> Creates a JDBC {@code PreparedStatement} wrapper.
     * <p><b>Enterprise:</b> Issues a Parse message (or retrieves from statement cache)
     * and prepares an off-heap Bind frame backed by {@code LoanedBuffer}.
     *
     * @param sql query string (parameters marked with {@code $1, $2, ...} for PostgreSQL)
     * @return a statement ready for binding; caller MUST close via try-with-resources
     * @throws PersistenceProviderException on parse failure
     * @since 0.5.0
     */
    PersistenceStatement prepare(String sql);

    // =========================================================================
    // Simple Query Execution (no parameters)
    // =========================================================================

    /**
     * Executes a SQL query and returns a zero-copy result set.
     *
     * <p><b>Enterprise hot path:</b> The returned {@link QueryResult} iterates
     * directly over off-heap buffers. No heap objects are created per row.
     *
     * <p><b>Community path:</b> The returned {@link QueryResult} wraps a JDBC
     * {@code ResultSet} and may allocate per-row.
     *
     * <p>For parameterised queries, use {@link #prepare(String)} instead to avoid
     * autoboxing and {@code Object[]} allocation.
     *
     * @param sql SQL query string (no parameters)
     * @return query result; caller MUST close via try-with-resources
     * @throws PersistenceProviderException on query failure
     */
    QueryResult executeQuery(String sql);

    /**
     * Executes a DML statement (INSERT, UPDATE, DELETE) without parameters.
     *
     * <p>For parameterised DML, use {@link #prepare(String)} instead.
     *
     * @param sql DML statement (no parameters)
     * @return number of rows affected
     * @throws PersistenceProviderException on execution failure
     */
    long executeUpdate(String sql);

    // =========================================================================
    // Transaction Control
    // =========================================================================

    /**
     * Begins an explicit transaction ({@code BEGIN}) with default isolation
     * ({@link TransactionIsolation#READ_COMMITTED}) and read-write mode.
     *
     * @throws PersistenceProviderException if already in a transaction
     */
    void beginTransaction();

    /**
     * Begins a transaction with specific isolation level and mutability rules.
     *
     * <p><b>Community:</b> Maps to JDBC {@code Connection.setTransactionIsolation()}
     * and {@code Connection.setReadOnly()}.
     * <p><b>Enterprise:</b> Emits a single
     * {@code BEGIN TRANSACTION ISOLATION LEVEL ... READ ONLY} wire protocol message.
     *
     * @param isolation isolation level (e.g., {@link TransactionIsolation#SERIALIZABLE})
     * @param readOnly  if {@code true}, enables backend optimizations for read-only
     *                  workloads (PostgreSQL skips write conflict detection)
     * @throws PersistenceProviderException if already in a transaction
     * @since 0.5.0
     */
    void beginTransaction(TransactionIsolation isolation, boolean readOnly);

    /**
     * Commits the current transaction ({@code COMMIT}).
     *
     * @throws PersistenceProviderException on commit failure
     */
    void commit();

    /**
     * Rolls back the current transaction ({@code ROLLBACK}).
     *
     * @throws PersistenceProviderException on rollback failure
     */
    void rollback();

    /**
     * Returns {@code true} if a transaction is currently active.
     *
     * @return transaction state
     */
    boolean inTransaction();

    // =========================================================================
    // Bulk Insert (COPY protocol — optional, tier-gated)
    // =========================================================================

    /**
     * Opens a bulk-insert session for the given table.
     *
     * <p>Enterprise tier: uses PostgreSQL {@code COPY ... FROM STDIN BINARY} for
     * O(1) per-row overhead — off-heap, zero-copy framing.
     * Community tier: default implementation returns {@link java.util.Optional#empty()}
     * — callers should fall back to batched {@link #prepare(String)} inserts.
     *
     * <p>The returned {@link BulkInserter} MUST be closed via try-with-resources.
     *
     * @param table the target table name (unquoted, schema-prefixed if necessary)
     * @return an {@link java.util.Optional} containing the bulk inserter, or empty if
     * this tier does not support the COPY protocol
     */
    default java.util.Optional<BulkInserter> openBulkInserter(String table) {
        return java.util.Optional.empty(); // Community default: no COPY support
    }

    // =========================================================================
    // Implementation Access (tier-blind unwrap)
    // =========================================================================

    /**
     * Unwraps this connection to an implementation-specific facility, if supported.
     *
     * <p>This is the SPI-level seam for integration bridges that must reach a
     * provider-specific backing object without the SPI naming any driver type.
     * It follows the {@link java.sql.Wrapper} idiom but stays
     * <strong>implementation-blind</strong>: the SPI does not reference JDBC,
     * off-heap buffers, or any tier-specific class. Each provider decides what,
     * if anything, it exposes.
     *
     * <p><b>Community:</b> The JDBC-backed connection unwraps to
     * {@code java.sql.Connection} (consumed by the JDBC compatibility bridge —
     * see ADR-017). Request-scoped forwarding wrappers delegate the unwrap to
     * their backing connection so the seam survives per-request session wrapping.
     * <p><b>Enterprise:</b> May unwrap to a wire-protocol session handle, or
     * return {@link java.util.Optional#empty()} when no compatible facility exists.
     *
     * <p>The default implementation returns this connection when it is itself an
     * instance of {@code type}, and {@link java.util.Optional#empty()} otherwise.
     * Unwrapping never transfers ownership: the returned object's lifecycle stays
     * bound to this connection — callers MUST NOT close it directly.
     *
     * @param type the requested facility type; never {@code null}
     * @param <T>  the facility type
     * @return an {@link java.util.Optional} holding the unwrapped instance, or
     *         empty if this provider exposes no such facility
     * @since 0.8.1
     */
    default <T> java.util.Optional<T> unwrap(Class<T> type) {
        return type.isInstance(this)
                ? java.util.Optional.of(type.cast(this))
                : java.util.Optional.empty();
    }

    // =========================================================================
    // Lifecycle
    // =========================================================================

    /**
     * Returns {@code true} if this connection is still open and usable.
     *
     * @return connection liveness
     */
    boolean isOpen();

    /**
     * Closes the connection and returns it to the pool.
     *
     * <p>If a transaction is active, it is rolled back before closing.
     * Idempotent — multiple calls are safe.
     */
    @Override
    void close();
}
