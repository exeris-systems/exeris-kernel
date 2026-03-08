/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.spi.persistence;

/**
 * SPI: Zero-allocation query binder and executor.
 *
 * <h2>The Autoboxing Kill (ADR-010 L0 Fix)</h2>
 * <p>This interface replaces the legacy {@code executeQuery(String sql, Object... params)}
 * pattern that caused autoboxing and {@code Object[]} allocation on every parameterised query.
 * With {@code PersistenceStatement}, primitive values are bound directly — no boxing,
 * no array allocation, no heap pressure at 1M+ QPS.
 *
 * <h2>Tier Separation</h2>
 * <ul>
 *   <li><b>Community:</b> Wraps a JDBC {@code PreparedStatement}. Binding calls
 *       delegate to {@code setInt}, {@code setString}, etc.</li>
 *   <li><b>Enterprise:</b> Backed by off-heap memory. Binding calls write binary
 *       data directly into the PostgreSQL Bind message buffer using
 *       {@code MemorySegment.set(ValueLayout.JAVA_INT, offset, value)}.
 *       Zero heap allocation for primitive types.</li>
 * </ul>
 *
 * <h2>Fluent API</h2>
 * <p>All {@code bind*} methods return {@code this} to enable fluent chaining:
 * <pre>{@code
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
 * }</pre>
 *
 * <h2>Parameter Indexing</h2>
 * <p>Parameters are <b>zero-based</b> ({@code $1} = index 0, {@code $2} = index 1).
 *
 * <h2>Thread Safety</h2>
 * <p>NOT thread-safe. Bound to the {@link PersistenceConnection} that created it.
 *
 * @see PersistenceConnection
 * @see QueryResult
 * @since 0.5.0
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
     * <p><b>Enterprise:</b> Writes 4 bytes Big Endian directly to off-heap Bind buffer.
     *
     * @param index zero-based parameter index
     * @param value int value to bind
     * @return this statement (fluent)
     */
    PersistenceStatement bindInt(int index, int value);

    /**
     * Binds a {@code long} value at the given parameter index.
     *
     * @param index zero-based parameter index
     * @param value long value to bind
     * @return this statement (fluent)
     */
    PersistenceStatement bindLong(int index, long value);

    /**
     * Binds a {@code short} value at the given parameter index.
     *
     * @param index zero-based parameter index
     * @param value short value to bind
     * @return this statement (fluent)
     */
    PersistenceStatement bindShort(int index, short value);

    /**
     * Binds a {@code float} value at the given parameter index.
     *
     * @param index zero-based parameter index
     * @param value float value to bind
     * @return this statement (fluent)
     */
    PersistenceStatement bindFloat(int index, float value);

    /**
     * Binds a {@code double} value at the given parameter index.
     *
     * @param index zero-based parameter index
     * @param value double value to bind
     * @return this statement (fluent)
     */
    PersistenceStatement bindDouble(int index, double value);

    /**
     * Binds a {@code boolean} value at the given parameter index.
     *
     * @param index zero-based parameter index
     * @param value boolean value to bind
     * @return this statement (fluent)
     */
    PersistenceStatement bindBoolean(int index, boolean value);

    // =========================================================================
    // Allocating Binders — Explicit opt-in
    // =========================================================================

    /**
     * Binds a {@link String} value at the given parameter index.
     *
     * <p><b>⚠ ALLOCATING:</b> In Enterprise tier, encodes String to UTF-8 off-heap.
     * Acceptable because the String already exists at the call site.
     *
     * @param index zero-based parameter index
     * @param value String value (may be {@code null} — treated as SQL NULL)
     * @return this statement (fluent)
     */
    PersistenceStatement bindString(int index, String value);

    /**
     * Binds raw bytes at the given parameter index.
     *
     * <p><b>⚠ ALLOCATING:</b> The byte array is a heap object. In Enterprise tier,
     * bytes are copied to off-heap buffer.
     *
     * @param index zero-based parameter index
     * @param value byte array (may be {@code null} — treated as SQL NULL)
     * @return this statement (fluent)
     */
    PersistenceStatement bindBytes(int index, byte[] value);

    /**
     * Binds a SQL NULL at the given parameter index.
     *
     * <p>Zero allocation in both tiers.
     *
     * @param index zero-based parameter index
     * @return this statement (fluent)
     */
    PersistenceStatement bindNull(int index);

    // =========================================================================
    // Execution
    // =========================================================================

    /**
     * Executes the bound query and returns a result set.
     *
     * <p><b>Enterprise:</b> Sends Parse/Bind/Describe/Execute/Sync pipeline.
     * <p><b>Community:</b> Delegates to JDBC {@code PreparedStatement.executeQuery()}.
     *
     * @return query result cursor; caller MUST close
     */
    QueryResult executeQuery();

    /**
     * Executes the bound DML statement (INSERT, UPDATE, DELETE).
     *
     * @return number of rows affected
     */
    long executeUpdate();

    /**
     * Releases statement resources. Idempotent.
     */
    @Override
    void close();
}
