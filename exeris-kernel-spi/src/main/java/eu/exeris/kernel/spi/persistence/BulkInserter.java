/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.spi.persistence;

import eu.exeris.kernel.spi.exceptions.persistence.PersistenceProviderException;

/**
 * SPI: Streaming bulk-insert contract — the {@code COPY} abstraction layer.
 *
 * <h2>Intent</h2>
 * <p>Provides a tier-aware streaming insert path. Enterprise implementations
 * use the PostgreSQL {@code COPY ... FROM STDIN BINARY} protocol for O(1) per-row
 * overhead and zero serialisation copies. Community implementations fall back to
 * batched {@code PreparedStatement.executeBatch()}.
 *
 * <h2>The Open-Core Contract</h2>
 * <table>
 *   <caption>How each tier carries the buffered rows to the database</caption>
 *   <tr><th>Tier</th><th>Behaviour</th></tr>
 *   <tr><td>Community</td><td>Batched {@code INSERT} via JDBC {@code PreparedStatement}</td></tr>
 *   <tr><td>Enterprise</td><td>PG COPY protocol — binary format, off-heap, zero-copy</td></tr>
 * </table>
 *
 * <h2>Usage</h2>
 * {@snippet lang="java" :
 * Optional<BulkInserter> maybeBulk = conn.openBulkInserter("events");
 * if (maybeBulk.isEmpty()) {
 *     // Community default: bulk inserter not available, fall back or skip
 *     return;
 * }
 *
 * try (BulkInserter bulk = maybeBulk.get()) {
 *     for (Event e : events) {
 *         PersistenceStatement row = bulk.row();
 *         row.bindString(0, e.id())
 *            .bindBytes(1, e.payload())
 *            .bindLong(2, e.timestamp());
 *         bulk.writeRow(row);
 *     }
 *     long inserted = bulk.flush();
 * }
 * }
 *
 * <h2>Lifecycle</h2>
 * <ol>
 *   <li>Obtain from {@link PersistenceConnection#openBulkInserter(String)}</li>
 *   <li>Call {@link #writeRow(PersistenceStatement)} for each row</li>
 *   <li>Call {@link #flush()} to commit the buffered rows to the database</li>
 *   <li>Close via try-with-resources — {@link #close()} sends the final COPY termination</li>
 * </ol>
 *
 * <p><b>Allocation:</b> {@link #row()} hands back the same binder on every call and allocates
 * nothing itself; what binding a value costs is the implementation's, and this interface does not
 * require it to be free.
 * <p><b>Thread confinement:</b> owner thread — not thread-safe, one {@code BulkInserter} per
 * virtual thread.
 * <p><b>Ownership:</b> the caller closes the inserter, and {@link #close()} without a preceding
 * {@link #flush()} discards the buffered rows; the binder from {@link #row()} belongs to the
 * inserter and is neither closed nor retained by the caller.
 *
 * @implSpec An implementation MUST NOT reference any PostgreSQL, HikariCP, or io_uring specific
 *           class — it operates purely on {@link PersistenceStatement} bindings.
 * @since 0.5
 * @see PersistenceConnection#openBulkInserter(String)
 */
public interface BulkInserter extends AutoCloseable {

    /**
     * Provides a reusable {@link PersistenceStatement} for binding a single row.
     *
     * @return the one row binder owned by this inserter — the same instance on every call;
     *         never {@code null}
     * @apiNote The binder is reused rather than replaced, so callers MUST reset all bindings
     *          before binding the next row.
     * @implNote The Enterprise tier resets off-heap column buffers in place, so a call allocates
     *           nothing.
     */
    PersistenceStatement row();

    /**
     * Submits the currently bound row to the internal write buffer.
     *
     * <p>The row is buffered, not sent: nothing reaches the database until {@link #flush()}.
     *
     * @param row the row binder (MUST be the instance returned by {@link #row()})
     * @throws PersistenceProviderException on serialisation failure
     * @implNote Enterprise writes the binary-encoded row into a {@code LoanedBuffer} COPY frame,
     *           with no heap allocation per row; Community buffers the bound parameters for a
     *           subsequent {@code executeBatch()}.
     */
    void writeRow(PersistenceStatement row);

    /**
     * Sends every row buffered since the last flush to the database.
     *
     * @return number of rows actually inserted
     * @throws PersistenceProviderException on flush/commit failure
     * @implNote Enterprise sends the complete COPY frame over the PG wire protocol; Community
     *           calls {@code PreparedStatement.executeBatch()} and sums the affected counts.
     */
    long flush();

    /**
     * Terminates the bulk-insert session, discarding any rows buffered since the last
     * {@link #flush()} and releasing the underlying database resources without committing them.
     *
     * <p>Calling {@code close()} on an already-closed inserter is a no-op.
     */
    @Override
    void close();
}
