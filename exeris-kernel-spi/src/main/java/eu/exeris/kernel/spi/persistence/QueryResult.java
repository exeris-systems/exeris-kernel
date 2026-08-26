/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.spi.persistence;

import eu.exeris.kernel.spi.exceptions.persistence.PersistenceProviderException;

/**
 * SPI: Tier-aware query result — the <b>CRITICAL</b> interface for zero-copy persistence.
 *
 * <h2>The Architect's Covenant (Enterprise Hot Path)</h2>
 * <p>In the Enterprise tier, the implementation of this interface MUST guarantee:
 * <ul>
 *   <li>{@link #next()} → O(1), <b>zero heap allocation</b></li>
 *   <li>{@link #row()} → returns the <b>same</b> {@link RowCursor} instance (flyweight)</li>
 *   <li>Row data is read directly from the off-heap {@code LoanedBuffer} backing
 *       the transport receive ring-buffer — <b>zero bytes are copied</b></li>
 *   <li>No {@code new} keyword, no autoboxing, no {@code ArrayList} growth inside
 *       the iteration loop</li>
 * </ul>
 *
 * <h2>Community Tier</h2>
 * <p>Community implementations MAY allocate per-row (wrapping JDBC {@code ResultSet}).
 * The interface is designed to allow both allocation-free and allocating paths.
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
 * <h2>Thread Safety</h2>
 * <p>NOT thread-safe. Bound to the connection that created it.
 *
 * @see RowCursor
 * @see PersistenceConnection
 * @since 0.5.0
 */
public interface QueryResult extends AutoCloseable {

    /**
     * Advances the cursor to the next row.
     *
     * <p><b>Enterprise contract:</b> This method MUST NOT allocate any heap object.
     * It repositions the internal flyweight cursor over the next row's offsets
     * in the off-heap receive buffer.
     *
     * @return {@code true} if positioned on a valid row; {@code false} if no more rows
     * @throws PersistenceProviderException on protocol or I/O error
     */
    boolean next();

    /**
     * Returns the current row's flyweight cursor.
     *
     * <p><b>CRITICAL:</b> The returned instance is <em>reused</em> across rows.
     * Callers MUST NOT store references to it across {@link #next()} calls.
     * Extract all needed values <em>before</em> calling {@code next()} again.
     *
     * <p><b>Enterprise:</b> Reads directly from off-heap {@code LoanedBuffer} via
     * {@code MemorySegment.get(ValueLayout, offset)} — zero copy, zero allocation.
     *
     * @return current row cursor (flyweight — do not retain across next())
     * @throws IllegalStateException if not positioned on a valid row
     */
    RowCursor row();

    /**
     * Number of rows affected by a DML statement (INSERT/UPDATE/DELETE).
     *
     * <p>For SELECT queries, returns {@code -1} or the row count if known.
     *
     * @return affected row count, or {@code -1} if not applicable
     */
    long rowsAffected();

    /**
     * Number of columns in the result set.
     *
     * @return column count ≥ 0
     */
    int columnCount();

    /**
     * Returns the command tag from the server (e.g., {@code "SELECT 100"},
     * {@code "INSERT 0 1"}).
     *
     * @return command tag string, or {@code null} if not available
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
