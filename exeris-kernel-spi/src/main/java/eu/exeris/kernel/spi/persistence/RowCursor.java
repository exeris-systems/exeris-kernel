/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.spi.persistence;

import java.lang.foreign.MemorySegment;
import java.time.Instant;
import java.util.UUID;

/**
 * SPI: Zero-copy, flyweight row accessor for {@link QueryResult} iteration.
 *
 * <h2>Flyweight Contract (The Architect's Covenant)</h2>
 * <p>A single {@code RowCursor} instance is shared across all rows in a
 * {@link QueryResult}. Each call to {@link QueryResult#next()} repositions
 * the cursor over the next row's data. Callers MUST NOT retain references
 * to this object across {@code next()} calls.
 *
 * <h2>Enterprise Hot Path</h2>
 * <p>In the Enterprise tier, all primitive accessors ({@link #getInt},
 * {@link #getLong}, {@link #getDouble}) read directly from off-heap
 * {@link MemorySegment} using {@code ValueLayout} — no heap allocation,
 * no boxing, no intermediate objects.
 *
 * <p>{@link #getSegment(int)} returns a zero-copy {@code MemorySegment.asSlice()}
 * into the transport receive buffer — the data is never copied to the heap.
 *
 * <h2>Community Tier</h2>
 * <p>Community implementations delegate to JDBC {@code ResultSet} methods.
 * Boxing MAY occur for primitive types in this tier.
 *
 * <h2>Allocating Methods</h2>
 * <p>{@link #getString(int)}, {@link #getBytes(int)}, and {@link #getUuid(int)}
 * are explicitly marked as <em>allocating</em>. They MUST be used sparingly
 * on the Enterprise hot path. Prefer {@link #getSegment(int)} for zero-copy access.
 *
 * @see QueryResult
 * @since 0.5.0
 */
public interface RowCursor {

    /*
     * Two rules hold for every accessor below, and are repeated on each of them so that a reader of
     * one method need not have read this one (ADR-080 ruling 1):
     *
     *   Column index — an index outside [0, columnCount()) throws IndexOutOfBoundsException. All
     *   thirteen accessors, uniformly. Before ADR-080 only getInt and getSegment said so; the other
     *   eleven behaved this way without declaring it, which is a coincidence rather than a contract
     *   and is what let two implementations read the same silence differently.
     *
     *   SQL NULL — a reference-typed accessor returns null; a primitive accessor throws
     *   NullPointerException, because it has no null to return. getLength returns -1 and getSegment
     *   throws: a read-only view of nothing is not a segment.
     */

    // =========================================================================
    // Primitive access — zero allocation (Enterprise hot path)
    // =========================================================================

    /**
     * Returns the {@code int} value at the given column index.
     *
     * <p><b>Enterprise:</b> {@code MemorySegment.get(JAVA_INT_UNALIGNED, offset)} — O(1), no alloc.
     *
     * @param column zero-based column index
     * @return int value
     * @throws IndexOutOfBoundsException if column is out of range
     * @throws NullPointerException      if the column value is SQL NULL (use {@link #isNull} first)
     */
    int getInt(int column);

    /**
     * Returns the {@code long} value at the given column index.
     *
     * @param column zero-based column index
     * @return long value
     * @throws IndexOutOfBoundsException if column is out of range
     * @throws NullPointerException      if the column value is SQL NULL (use {@link #isNull} first)
     */
    long getLong(int column);

    /**
     * Returns the {@code short} value at the given column index.
     *
     * @param column zero-based column index
     * @return short value
     * @throws IndexOutOfBoundsException if column is out of range
     * @throws NullPointerException      if the column value is SQL NULL (use {@link #isNull} first)
     */
    short getShort(int column);

    /**
     * Returns the {@code float} value at the given column index.
     *
     * @param column zero-based column index
     * @return float value
     * @throws IndexOutOfBoundsException if column is out of range
     * @throws NullPointerException      if the column value is SQL NULL (use {@link #isNull} first)
     */
    float getFloat(int column);

    /**
     * Returns the {@code double} value at the given column index.
     *
     * @param column zero-based column index
     * @return double value
     * @throws IndexOutOfBoundsException if column is out of range
     * @throws NullPointerException      if the column value is SQL NULL (use {@link #isNull} first)
     */
    double getDouble(int column);

    /**
     * Returns the {@code boolean} value at the given column index.
     *
     * @param column zero-based column index
     * @return boolean value
     * @throws IndexOutOfBoundsException if column is out of range
     * @throws NullPointerException      if the column value is SQL NULL (use {@link #isNull} first)
     */
    boolean getBoolean(int column);

    /**
     * Checks if the value at the given column is SQL NULL.
     *
     * <p><b>Enterprise:</b> Checks the length array — O(1), no alloc.
     *
     * @param column zero-based column index
     * @return {@code true} if NULL
     * @throws IndexOutOfBoundsException if column is out of range
     */
    boolean isNull(int column);

    // =========================================================================
    // Zero-copy segment access (Enterprise exclusive)
    // =========================================================================

    /**
     * Returns a zero-copy {@link MemorySegment} slice of the raw column bytes.
     *
     * <p><b>Enterprise:</b> {@code buffer.asSlice(offset, length)} — O(1), zero copy.
     * The returned segment points directly into the transport receive buffer.
     * It is valid only until the next {@link QueryResult#next()} call.
     *
     * <p><b>Community:</b> MAY return a heap-backed segment wrapping a byte array.
     *
     * @param column zero-based column index
     * @return segment containing raw column bytes (read-only)
     * @throws IndexOutOfBoundsException if column is out of range
     * @throws NullPointerException      if the column value is SQL NULL (use {@link #isNull} first)
     */
    MemorySegment getSegment(int column);

    /**
     * Returns the byte length of the raw column value.
     *
     * <p>Useful for pre-allocating destination buffers or deciding whether to
     * use {@link #getSegment} vs {@link #getString}.
     *
     * @param column zero-based column index
     * @return byte length, or {@code -1} if NULL
     * @throws IndexOutOfBoundsException if column is out of range
     */
    int getLength(int column);

    // =========================================================================
    // Allocating access — explicit opt-in
    // =========================================================================

    /**
     * Returns the column value as a Java {@link String}.
     *
     * <p><b>⚠ ALLOCATING:</b> Creates a new String from UTF-8 bytes.
     * On the Enterprise hot path, prefer {@link #getSegment(int)} and decode
     * only when crossing the API boundary to user code.
     *
     * @param column zero-based column index
     * @return String value, or {@code null} if SQL NULL
     * @throws IndexOutOfBoundsException if column is out of range
     */
    String getString(int column);

    /**
     * Returns the column value as a byte array.
     *
     * <p><b>⚠ ALLOCATING:</b> Copies bytes from off-heap to a new {@code byte[]}.
     * Prefer {@link #getSegment(int)} for zero-copy access.
     *
     * @param column zero-based column index
     * @return byte array, or {@code null} if SQL NULL
     * @throws IndexOutOfBoundsException if column is out of range
     */
    byte[] getBytes(int column);

    /**
     * Returns the column value as a {@link UUID}.
     *
     * <p><b>⚠ ALLOCATING:</b> Creates a new UUID object.
     * For high-throughput paths, read the raw 16 bytes via {@link #getSegment(int)}.
     *
     * @param column zero-based column index
     * @return UUID value, or {@code null} if SQL NULL
     * @throws IndexOutOfBoundsException if column is out of range
     */
    UUID getUuid(int column);

    /**
     * Returns the column value as a {@link Instant} (UTC, no zone offset).
     *
     * <p><b>⚠ ALLOCATING:</b> Creates a new Instant object. Community implementations
     * delegate to {@code ResultSet.getTimestamp(...).toInstant()} (two heap allocations).
     * Enterprise implementations decode 8 bytes from the off-heap result buffer and
     * allocate exactly one Instant per call.
     *
     * <p>Unlike primitive getters, this reference-typed accessor returns {@code null}
     * for SQL NULL rather than throwing. Callers that map NULL to a sentinel (e.g.
     * {@code Instant.MAX} for "no timeout") MUST perform that mapping at the call site.
     * See ADR-022 §5 for the NULL-handling contract.
     *
     * @param column zero-based column index
     * @return Instant value, or {@code null} if SQL NULL
     * @throws IndexOutOfBoundsException if column is out of range
     * @since 0.8.0
     */
    Instant getInstant(int column);

    // =========================================================================
    // Metadata
    // =========================================================================

    /**
     * Number of columns in the current row.
     *
     * @return column count
     */
    int columnCount();

    /**
     * Returns {@code true} if this cursor is positioned on a valid row.
     *
     * @return validity state
     */
    boolean isValid();
}
