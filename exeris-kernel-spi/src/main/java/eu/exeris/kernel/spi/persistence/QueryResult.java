/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.spi.persistence;

import eu.exeris.kernel.spi.exceptions.persistence.PersistenceProviderException;

/**
 * SPI: Tier-aware query result — the <b>CRITICAL</b> interface for zero-copy persistence.
 *
 * <h2>Lifecycle</h2>
 * <pre>
 *  conn.executeQuery(sql) → QueryResult
 *      while (result.next()) {
 *          result.row().getInt(0);      // O(1), no alloc (Enterprise)
 *          result.row().getSegment(1);  // zero-copy MemorySegment slice
 *      }
 *      result.close();  // releases LoanedBuffer back to pool
 * </pre>
 *
 * <p><b>Allocation:</b> zero-alloc on hot path — {@link #next()} allocates nothing and
 * {@link #row()} hands back one flyweight for the whole iteration; an implementation layered over
 * a row-at-a-time cursor MAY allocate per row instead, and the interface is shaped to permit both.
 * <p><b>Thread confinement:</b> owner thread — not thread-safe, bound to the connection that
 * created it, which is itself confined to one virtual thread.
 * <p><b>Ownership:</b> the caller closes the result, which releases the off-heap buffers back to
 * the pool; the {@link RowCursor} from {@link #row()} is owned by this result and is never the
 * caller's to close or retain.
 *
 * @implSpec An Enterprise-tier implementation guarantees the zero-copy covenant:
 *           {@link #next()} is O(1) with zero heap allocation; {@link #row()} returns the
 *           <b>same</b> {@link RowCursor} instance on every call; row data is read directly from
 *           the off-heap {@code LoanedBuffer} backing the transport receive ring-buffer, with
 *           zero bytes copied; and the iteration loop contains no {@code new}, no autoboxing and
 *           no growing collection.
 * @implNote The Community binding layers this interface over a JDBC {@code ResultSet}, which is a
 *           row-at-a-time cursor, so it allocates per row rather than meeting the covenant above.
 * @since 0.5
 * @see RowCursor
 * @see PersistenceConnection
 */
public interface QueryResult extends AutoCloseable {

    /**
     * Advances the flyweight cursor to the next row; any {@link java.lang.foreign.MemorySegment}
     * taken from the previous row is invalid once this returns.
     *
     * @return {@code true} if positioned on a valid row; {@code false} if no more rows
     * @throws PersistenceProviderException
     *         {@value eu.exeris.kernel.spi.exceptions.KernelErrorCodes#EX_PERS_5003} on a
     *         protocol or I/O error
     * @implSpec An implementation MUST NOT allocate any heap object here — it repositions the
     *           internal flyweight cursor over the next row's offsets in the off-heap receive
     *           buffer.
     */
    boolean next();

    /**
     * Returns the flyweight cursor positioned on the current row.
     *
     * @return the one row cursor shared by every row of this result
     * @throws IllegalStateException if not positioned on a valid row
     * @apiNote The instance is reused across rows, so callers MUST NOT store a reference to it
     *          across {@link #next()} calls: read every value the row needs <em>before</em>
     *          calling {@code next()} again.
     * @implNote Enterprise reads through {@code MemorySegment.get(ValueLayout, offset)} straight
     *           out of the off-heap {@code LoanedBuffer} — zero copy, zero allocation.
     */
    RowCursor row();

    /**
     * Number of rows affected by a DML statement (INSERT/UPDATE/DELETE).
     *
     * @return affected row count; {@code -1} when the count does not apply or is not known,
     *         which is the usual answer for a SELECT
     */
    long rowsAffected();

    /**
     * Number of columns in the result set.
     *
     * @return column count ≥ 0, and the exclusive upper bound for every {@link RowCursor}
     *         accessor's column index
     */
    int columnCount();

    /**
     * Returns the command tag the server reported for this statement, e.g. {@code "SELECT 100"}
     * or {@code "INSERT 0 1"}.
     *
     * @return command tag string, or {@code null} if the provider does not surface one
     */
    String commandTag();

    /**
     * Releases all resources associated with this result — off-heap buffers,
     * protocol state, and internal cursors.
     *
     * <p>Idempotent — multiple calls are safe. After close, all other methods
     * throw {@link IllegalStateException}.
     */
    @Override
    void close();
}
