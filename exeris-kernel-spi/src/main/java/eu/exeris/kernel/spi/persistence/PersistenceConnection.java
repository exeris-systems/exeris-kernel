/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
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
 * <h2>Prepared Statement Path (ADR-010)</h2>
 * <p>Parameterised queries go through {@link #prepare(String)}, which yields a
 * {@link PersistenceStatement} with typed binders ({@code bindInt}, {@code bindLong},
 * etc.) and so avoids autoboxing and {@code Object[]} allocation entirely. This SPI
 * offers no {@code executeQuery(sql, Object...)} form.
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
 * <p><b>Allocation:</b> allocates one {@link PersistenceStatement} per {@link #prepare(String)}.
 * A {@link QueryResult} may be allocated per query or once per connection and reset on each one —
 * this interface does not require either. Per-row allocation belongs to the result.
 * <p><b>Thread confinement:</b> owner thread — a connection is not thread-safe, and each virtual
 * thread MUST own its own.
 * <p><b>Ownership:</b> the caller closes the connection, which rolls back any open transaction
 * and returns the underlying resource to the pool; statements and results opened from it are
 * closed by the caller in their own right, and an object obtained through
 * {@link #unwrap(Class)} is never the caller's to close.
 *
 * @since 0.5
 * @see PersistenceEngine
 * @see PersistenceStatement
 * @see QueryResult
 */
@SuppressWarnings("PMD.TooManyMethods") // SPI contract surface — method count is intrinsic
public interface PersistenceConnection extends AutoCloseable {

    // =========================================================================
    // Prepared Statement Factory (Zero-Allocation Path)
    // =========================================================================

    /**
     * Prepares a statement for execution with typed parameter binding.
     *
     * @param sql query string (parameters marked with {@code $1, $2, ...} for PostgreSQL)
     * @return a statement ready for binding; caller MUST close via try-with-resources
     * @throws PersistenceProviderException
     *         {@value eu.exeris.kernel.spi.exceptions.KernelErrorCodes#EX_PERS_5003} if the
     *         server rejects the statement at parse time
     * @apiNote This is the query path to reach for: binding through
     *          {@link PersistenceStatement} keeps parameters off the heap, where an
     *          {@code Object...} form would box every primitive.
     * @implNote Community creates a JDBC {@code PreparedStatement} wrapper; Enterprise issues a
     *           Parse message (or takes the entry from its statement cache) and prepares an
     *           off-heap Bind frame backed by a {@code LoanedBuffer}.
     * @since 0.5
     */
    PersistenceStatement prepare(String sql);

    // =========================================================================
    // Simple Query Execution (no parameters)
    // =========================================================================

    /**
     * Executes a parameterless SQL query and returns a result set positioned before the first row.
     *
     * @param sql SQL query string (no parameters)
     * @return query result; caller MUST close via try-with-resources
     * @throws PersistenceProviderException
     *         {@value eu.exeris.kernel.spi.exceptions.KernelErrorCodes#EX_PERS_5003} on a SQL,
     *         protocol or I/O failure
     * @apiNote Use {@link #prepare(String)} for anything parameterised; interpolating values into
     *          {@code sql} is both an injection risk and a cache miss on every call.
     * @implNote The Enterprise result iterates directly over off-heap buffers and creates no heap
     *           object per row; the Community result wraps a JDBC {@code ResultSet} and may
     *           allocate per row.
     */
    QueryResult executeQuery(String sql);

    /**
     * Executes a parameterless DML statement (INSERT, UPDATE, DELETE).
     *
     * @param sql DML statement (no parameters)
     * @return number of rows affected
     * @throws PersistenceProviderException
     *         {@value eu.exeris.kernel.spi.exceptions.KernelErrorCodes#EX_PERS_5003} on a SQL,
     *         protocol or I/O failure
     * @apiNote Use {@link #prepare(String)} for parameterised DML.
     */
    long executeUpdate(String sql);

    // =========================================================================
    // Transaction Control
    // =========================================================================

    /**
     * Begins an explicit transaction ({@code BEGIN}) with default isolation
     * ({@link TransactionIsolation#READ_COMMITTED}) and read-write mode.
     *
     * @throws PersistenceProviderException
     *         {@value eu.exeris.kernel.spi.exceptions.KernelErrorCodes#EX_PERS_5003} if already
     *         in a transaction
     */
    void beginTransaction();

    /**
     * Begins a transaction with specific isolation level and mutability rules.
     *
     * @param isolation isolation level (e.g., {@link TransactionIsolation#SERIALIZABLE})
     * @param readOnly  if {@code true}, enables backend optimizations for read-only
     *                  workloads (PostgreSQL skips write conflict detection)
     * @throws PersistenceProviderException
     *         {@value eu.exeris.kernel.spi.exceptions.KernelErrorCodes#EX_PERS_5003} if already
     *         in a transaction
     * @implNote Community maps this onto JDBC {@code Connection.setTransactionIsolation()} and
     *           {@code Connection.setReadOnly()}; Enterprise emits a single
     *           {@code BEGIN TRANSACTION ISOLATION LEVEL ... READ ONLY} wire-protocol message.
     * @since 0.5
     */
    void beginTransaction(TransactionIsolation isolation, boolean readOnly);

    /**
     * Commits the current transaction ({@code COMMIT}).
     *
     * @throws PersistenceProviderException
     *         {@value eu.exeris.kernel.spi.exceptions.KernelErrorCodes#EX_PERS_5003} on commit
     *         failure
     */
    void commit();

    /**
     * Rolls back the current transaction ({@code ROLLBACK}).
     *
     * @throws PersistenceProviderException
     *         {@value eu.exeris.kernel.spi.exceptions.KernelErrorCodes#EX_PERS_5003} on rollback
     *         failure
     */
    void rollback();

    /**
     * Reports whether an explicit transaction is open on this connection.
     *
     * @return {@code true} between {@code beginTransaction} and the {@link #commit()} or
     *         {@link #rollback()} that ends it; {@code false} under auto-commit semantics
     */
    boolean inTransaction();

    // =========================================================================
    // Bulk Insert (COPY protocol — optional, tier-gated)
    // =========================================================================

    /**
     * Opens a bulk-insert session for the given table, where the provider supports one.
     *
     * @param table the target table name (unquoted, schema-prefixed if necessary)
     * @return an {@link java.util.Optional} containing the bulk inserter, or empty if
     *         this provider offers no COPY-style path; the caller closes a present inserter via
     *         try-with-resources
     * @apiNote An empty result is an ordinary answer, not a failure — a caller that needs to work
     *          on both tiers falls back to batched {@link #prepare(String)} inserts.
     * @implNote Enterprise uses PostgreSQL {@code COPY ... FROM STDIN BINARY} for O(1) per-row
     *           overhead with off-heap, zero-copy framing; the default implementation, which
     *           Community takes, returns {@link java.util.Optional#empty()}.
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
     * <p>The default implementation returns this connection when it is itself an
     * instance of {@code type}, and {@link java.util.Optional#empty()} otherwise.
     *
     * @param type the requested facility type; never {@code null}
     * @param <T>  the facility type
     * @return an {@link java.util.Optional} holding the unwrapped instance, or
     *         empty if this provider exposes no such facility
     * @implSpec Unwrapping never transfers ownership: the returned object's lifecycle stays bound
     *           to this connection, and callers MUST NOT close it directly. A forwarding wrapper
     *           MUST delegate the unwrap to the connection it wraps, so that the seam survives
     *           per-request session wrapping.
     * @implNote The Community JDBC-backed connection unwraps to {@code java.sql.Connection},
     *           consumed by the JDBC compatibility bridge (ADR-017); an Enterprise connection may
     *           unwrap to a wire-protocol session handle, or to
     *           {@link java.util.Optional#empty()} when no compatible facility exists.
     * @since 0.8
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
     * Reports whether this connection may still be used for queries.
     *
     * @return {@code true} while the connection can still serve queries; {@code false} once it
     *         has been closed or otherwise invalidated
     */
    boolean isOpen();

    /**
     * Closes the connection and returns it to the pool, rolling back any transaction still open.
     *
     * <p>Idempotent — multiple calls are safe.
     */
    @Override
    void close();
}
