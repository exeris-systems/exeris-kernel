/*
 * Copyright (C) 2025-2026 Exeris. All rights reserved.
 *
 * This code is part of the Exeris Systems.
 * Distributed under the proprietary Exeris Software License.
 * Unauthorized copying or distribution is prohibited.
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
 * @since 0.5.0
 * @see PersistenceEngine
 * @see PersistenceStatement
 * @see QueryResult
 */
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
    int executeUpdate(String sql);

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

