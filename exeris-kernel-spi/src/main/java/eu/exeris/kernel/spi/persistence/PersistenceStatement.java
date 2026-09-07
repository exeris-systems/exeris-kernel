/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.spi.persistence;

import java.time.Instant;
import java.util.UUID;

/**
 * SPI: Zero-allocation query binder and executor.
 *
 * <h2>The Autoboxing Kill (ADR-010)</h2>
 * <p>Primitive values are bound through typed binders rather than an {@code Object...} varargs —
 * no boxing, no array allocation, no heap pressure at 1M+ QPS.
 *
 * <h2>Fluent API</h2>
 * <p>All {@code bind*} methods return {@code this} to enable fluent chaining:
 * {@snippet lang="java" :
 * try (PersistenceStatement stmt = conn.prepare(
 *         "SELECT * FROM users WHERE id = $1 AND score > $2")) {
 *     stmt.bindInt(0, userId)
 *         .bindDouble(1, minScore);
 *     try (QueryResult rs = stmt.executeQuery()) {
 *         while (rs.next()) {
 *             long id = rs.row().getLong(0);
 *         }
 *     }
 * }
 * }
 *
 * <h2>Parameter Indexing</h2>
 * <p>Parameters are <b>zero-based</b> ({@code $1} = index 0, {@code $2} = index 1).
 *
 * <p><b>Allocation:</b> zero-alloc on hot path for the primitive binders and
 * {@link #bindNull(int)}; allocates for {@link #bindString}, {@link #bindBytes},
 * {@link #bindUuid} and {@link #bindInstant}, whose values are heap objects already at the call
 * site, and one {@link QueryResult} per {@link #executeQuery()}.
 * <p><b>Thread confinement:</b> owner thread — not thread-safe, bound to the
 * {@link PersistenceConnection} that created it.
 * <p><b>Ownership:</b> the caller closes the statement, and separately closes each
 * {@link QueryResult} obtained from {@link #executeQuery()}.
 *
 * @implNote Community wraps a JDBC {@code PreparedStatement}, delegating each binder to
 *           {@code setInt}, {@code setString} and so on. Enterprise is backed by off-heap memory
 *           and writes binary data straight into the PostgreSQL Bind message buffer through
 *           {@code MemorySegment.set(ValueLayout.JAVA_INT, offset, value)}, allocating nothing
 *           for primitive types.
 * @since 0.5
 * @see PersistenceConnection
 * @see QueryResult
 */
// SPI contract: one method per JVM primitive type + String/bytes/null + execute/close
@SuppressWarnings("PMD.TooManyMethods") // intentional: one binder per JVM primitive type (zero-boxing contract)
public interface PersistenceStatement extends AutoCloseable {

    // =========================================================================
    // Primitive Binders — Zero Allocation (Enterprise hot path)
    // =========================================================================

    /**
     * Binds an {@code int} value at the given parameter index.
     *
     * @param index zero-based parameter index
     * @param value int value to bind
     * @return this statement, for chaining
     * @implNote Enterprise writes 4 big-endian bytes straight into the off-heap Bind buffer.
     */
    PersistenceStatement bindInt(int index, int value);

    /**
     * Binds a {@code long} value at the given parameter index.
     *
     * @param index zero-based parameter index
     * @param value long value to bind
     * @return this statement, for chaining
     */
    PersistenceStatement bindLong(int index, long value);

    /**
     * Binds a {@code short} value at the given parameter index.
     *
     * @param index zero-based parameter index
     * @param value short value to bind
     * @return this statement, for chaining
     */
    PersistenceStatement bindShort(int index, short value);

    /**
     * Binds a {@code float} value at the given parameter index.
     *
     * @param index zero-based parameter index
     * @param value float value to bind
     * @return this statement, for chaining
     */
    PersistenceStatement bindFloat(int index, float value);

    /**
     * Binds a {@code double} value at the given parameter index.
     *
     * @param index zero-based parameter index
     * @param value double value to bind
     * @return this statement, for chaining
     */
    PersistenceStatement bindDouble(int index, double value);

    /**
     * Binds a {@code boolean} value at the given parameter index.
     *
     * @param index zero-based parameter index
     * @param value boolean value to bind
     * @return this statement, for chaining
     */
    PersistenceStatement bindBoolean(int index, boolean value);

    // =========================================================================
    // Allocating Binders — Explicit opt-in
    // =========================================================================

    /**
     * Binds a {@link String} value at the given parameter index.
     *
     * <p><b>⚠ ALLOCATING:</b> Acceptable because the String already exists at the call site.
     *
     * @param index zero-based parameter index
     * @param value String value (may be {@code null} — treated as SQL NULL)
     * @return this statement, for chaining
     * @implNote Enterprise encodes the String to UTF-8 off-heap.
     */
    PersistenceStatement bindString(int index, String value);

    /**
     * Binds a {@link UUID} value at the given parameter index, typed as a native {@code uuid}
     * column rather than as text.
     *
     * @param index zero-based parameter index
     * @param value UUID value (may be {@code null} — treated as SQL NULL)
     * @return this statement, for chaining
     * @implNote Community delegates to {@code PreparedStatement.setObject(index, uuid)}, which
     *           the pgjdbc driver maps to PostgreSQL OID 2950 ({@code uuid}), so no implicit
     *           {@code varchar→uuid} cast is required; Enterprise writes the 16-byte binary UUID
     *           representation straight into the off-heap Bind message buffer.
     */
    PersistenceStatement bindUuid(int index, UUID value);

    /**
     * Binds raw bytes at the given parameter index.
     *
     * <p><b>⚠ ALLOCATING:</b> The byte array is a heap object.
     *
     * @param index zero-based parameter index
     * @param value byte array (may be {@code null} — treated as SQL NULL)
     * @return this statement, for chaining
     * @implNote Enterprise copies the bytes into the off-heap Bind buffer.
     */
    PersistenceStatement bindBytes(int index, byte[] value);

    /**
     * Binds a {@link Instant} value at the given parameter index. {@code null}
     * is treated as SQL NULL (typed as {@code TIMESTAMP WITH TIME ZONE}).
     *
     * <p>The {@code Instant.MAX ↔ SQL NULL} convention used by {@code FlowSnapshot.timeout()}
     * is a caller-side concern — this binder treats {@code null} as NULL and any other
     * {@code Instant} value (including {@code Instant.MAX}) as the binary timestamp.
     * See ADR-022 §5.
     *
     * @param index zero-based parameter index
     * @param value Instant value (may be {@code null} — treated as SQL NULL)
     * @return this statement, for chaining
     * @implNote Community delegates to {@code PreparedStatement.setTimestamp(index + 1,
     *           Timestamp.from(value))}, allocating one {@code java.sql.Timestamp} per call;
     *           Enterprise writes 8 bytes — microseconds since the Postgres epoch
     *           2000-01-01 UTC, big-endian — straight into the off-heap Bind message buffer,
     *           allocating nothing.
     * @since 0.8
     */
    PersistenceStatement bindInstant(int index, Instant value);

    /**
     * Binds a SQL NULL at the given parameter index.
     *
     * @param index zero-based parameter index
     * @return this statement, for chaining
     * @implNote Allocates nothing in either tier.
     */
    PersistenceStatement bindNull(int index);

    // =========================================================================
    // Execution
    // =========================================================================

    /**
     * Executes the statement with the parameters bound so far and returns a result set
     * positioned before the first row.
     *
     * @return query result cursor; caller MUST close
     * @implNote Enterprise sends the Parse/Bind/Describe/Execute/Sync pipeline; Community
     *           delegates to JDBC {@code PreparedStatement.executeQuery()}.
     */
    QueryResult executeQuery();

    /**
     * Executes the bound DML statement (INSERT, UPDATE, DELETE).
     *
     * @return number of rows affected
     */
    long executeUpdate();

    /**
     * Releases the statement's resources — the prepared handle and, in the Enterprise tier, the
     * off-heap Bind buffer. Idempotent.
     */
    @Override
    void close();
}
