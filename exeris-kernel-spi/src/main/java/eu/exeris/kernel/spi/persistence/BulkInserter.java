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
 *   <tr><th>Tier</th><th>Behaviour</th></tr>
 *   <tr><td>Community</td><td>Batched {@code INSERT} via JDBC {@code PreparedStatement}</td></tr>
 *   <tr><td>Enterprise</td><td>PG COPY protocol — binary format, off-heap, zero-copy</td></tr>
 * </table>
 *
 * <h2>Usage</h2>
 * <pre>{@code
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
 * }</pre>
 *
 * <h2>Lifecycle</h2>
 * <ol>
 *   <li>Obtain from {@link PersistenceConnection#openBulkInserter(String)}</li>
 *   <li>Call {@link #writeRow(PersistenceStatement)} for each row</li>
 *   <li>Call {@link #flush()} to commit the buffered rows to the database</li>
 *   <li>Close via try-with-resources — {@link #close()} sends the final COPY termination</li>
 * </ol>
 *
 * <h2>Thread Safety</h2>
 * <p>NOT thread-safe. One {@code BulkInserter} per virtual thread.
 *
 * <h2>The Wall (SPI Compliance)</h2>
 * <p>This interface MUST NOT reference any PostgreSQL, HikariCP, or io_uring
 * specific class. It operates purely on {@link PersistenceStatement} bindings.
 *
 * @see PersistenceConnection#openBulkInserter(String)
 * @since 0.5
 */
public interface BulkInserter extends AutoCloseable {

    /**
     * Provides a reusable {@link PersistenceStatement} for binding a single row.
     *
     * <p>The same instance is returned on every call — callers MUST reset
     * all bindings before reuse. The Enterprise tier uses off-heap column
     * buffers that are reset in-place, with zero allocation per call.
     *
     * @return reusable row binder; never {@code null}
     */
    PersistenceStatement row();

    /**
     * Submits the currently bound row to the internal write buffer.
     *
     * <p>Enterprise: writes the binary-encoded row into a {@code LoanedBuffer}
     * COPY frame — zero heap allocation per row.
     * Community: buffers the bound parameters for a subsequent {@code executeBatch()}.
     *
     * @param row the row binder (MUST be the instance returned by {@link #row()})
     * @throws PersistenceProviderException on serialisation failure
     */
    void writeRow(PersistenceStatement row);

    /**
     * Flushes all buffered rows to the database and returns the number of rows inserted.
     *
     * <p>Enterprise: sends the complete COPY frame via the PG wire protocol.
     * Community: calls {@code PreparedStatement.executeBatch()} and returns the total
     * affected count.
     *
     * @return number of rows actually inserted
     * @throws PersistenceProviderException on flush/commit failure
     */
    long flush();

    /**
     * Terminates the bulk-insert session.
     *
     * <p>If {@link #flush()} was not called, pending buffered rows are discarded
     * and the underlying database resources are released without committing.
     * Calling {@code close()} on an already-closed inserter is a no-op.
     */
    @Override
    void close();
}
