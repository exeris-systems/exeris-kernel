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
 * the cursor over the next row's data.
 *
 * <h2>The three answers every accessor gives</h2>
 * <p>Every accessor states what it does with an out-of-range column index and with a SQL NULL,
 * and both answers are uniform across the thirteen (ADR-080). An index outside
 * {@code [0, columnCount())} throws {@link IndexOutOfBoundsException}. On SQL NULL the answer
 * follows the <em>return type</em>, not the column: a reference-typed accessor returns
 * {@code null}, a primitive accessor throws {@link NullPointerException} because it has no null
 * to return, {@link #getLength} returns {@code -1}, and {@link #getSegment} throws — a read-only
 * view of nothing is not a segment.
 *
 * <p><b>Allocation:</b> zero-alloc on hot path for the primitive accessors, {@link #isNull} and
 * {@link #getLength}; {@link #getSegment(int)} copies no bytes, handing back a view rather than
 * data; allocates for {@link #getString(int)}, {@link #getBytes(int)}, {@link #getUuid(int)} and
 * {@link #getInstant(int)} — one object per call, by design rather than by accident.
 * <p><b>Thread confinement:</b> owner thread — the cursor is the flyweight of its
 * {@link QueryResult}, which is not thread-safe, and its position is only meaningful to the
 * thread driving the iteration.
 * <p><b>Ownership:</b> the {@link QueryResult} owns this cursor; the caller never closes it and
 * MUST NOT retain it across {@link QueryResult#next()}. A {@link MemorySegment} from
 * {@link #getSegment(int)} is a view into the result's buffer and dies with the next row.
 *
 * @implSpec In the Enterprise tier the primitive accessors ({@link #getInt}, {@link #getLong},
 *           {@link #getDouble}) read directly from an off-heap {@link MemorySegment} through
 *           {@code ValueLayout} — no heap allocation, no boxing, no intermediate object — and
 *           {@link #getSegment(int)} returns a {@code MemorySegment.asSlice()} into the transport
 *           receive buffer, copying nothing to the heap.
 * @apiNote On a hot path prefer {@link #getSegment(int)} and decode at the boundary to user code;
 *          the allocating accessors are the deliberate opt-out from that.
 * @implNote Community implementations delegate to JDBC {@code ResultSet} methods, where boxing
 *           may occur for primitive types.
 * @since 0.5
 * @see QueryResult
 */
public interface RowCursor {

    /*
     * Two rules hold for every accessor below, and are repeated on each of them so that a reader of
     * one method need not have read this one (ADR-080 ruling 1):
     *
     *   Column index — an index outside [0, columnCount()) throws IndexOutOfBoundsException. All
     *   thirteen accessors, uniformly. An accessor that behaves this way without declaring it is a
     *   coincidence rather than a contract, and is what lets two implementations read the same
     *   silence differently.
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
     * Returns the column value rendered as a Java {@link String}, for any column type inside the
     * accessor's domain (ADR-080 §2).
     *
     * <p><b>⚠ ALLOCATING:</b> Creates a new String from UTF-8 bytes.
     * On the Enterprise hot path, prefer {@link #getSegment(int)} and decode
     * only when crossing the API boundary to user code.
     *
     * <p>A column whose type falls outside that domain is <em>refused</em> rather than rendered:
     * decoding an unimplemented type's bytes as text yields a plausible wrong answer on a data
     * path. The decision reads the column's declared type name and is a property of the column,
     * not of the row — so a SQL NULL in an unsupported column refuses too, since {@code null}
     * would claim "no value here" when the truth is "this column cannot be rendered".
     *
     * @param column zero-based column index
     * @return String value, or {@code null} if SQL NULL in a supported column
     * @throws IndexOutOfBoundsException if column is out of range
     * @throws eu.exeris.kernel.spi.exceptions.persistence.PersistenceProviderException
     *         {@value eu.exeris.kernel.spi.exceptions.KernelErrorCodes#EX_PERS_5008} if the
     *         column's type is outside the accessor's domain
     * @implNote The rendering guarantee contracts the server's own {@code <type>_out} output over
     *           a set measured on PostgreSQL, so the Community driver applies both the guarantee
     *           and the refusal on PostgreSQL connections, detected once per result set. On any
     *           other engine this stays the JDBC pass-through, with no ADR-080 §2 promise
     *           attached — H2 in PostgreSQL compatibility mode, for instance, renders {@code bool}
     *           as {@code TRUE} where PostgreSQL renders {@code t}.
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
     * @since 0.8
     */
    Instant getInstant(int column);

    // =========================================================================
    // Metadata
    // =========================================================================

    /**
     * Number of columns in the current row.
     *
     * @return the column count — the exclusive upper bound every accessor's {@code column}
     *         argument is checked against
     */
    int columnCount();

    /**
     * Reports whether this cursor currently sits on a row whose values can be read.
     *
     * @return {@code true} while positioned on a row; {@code false} before the first
     *         {@link QueryResult#next()} and after it has reported exhaustion
     */
    boolean isValid();
}
