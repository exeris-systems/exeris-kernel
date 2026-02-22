/*
 * Copyright (C) 2025-2026 Exeris. All rights reserved.
 *
 * This code is part of the Exeris Systems.
 * Distributed under the proprietary Exeris Software License.
 * Unauthorized copying or distribution is prohibited.
 */
package eu.exeris.kernel.spi.memory;

import java.lang.foreign.MemorySegment;

/**
 * SPI: A borrowed, reference-counted buffer backed by a {@link MemorySegment}.
 *
 * <h2>Zero-Copy Contract</h2>
 * <p>Data MUST never be copied between heap and off-heap. All fragment operations
 * ({@link #slice}, {@link #view}) return views into the <em>same</em> underlying
 * {@link MemorySegment} — zero bytes are moved. The parent buffer's reference count
 * is incremented by each view/slice, ensuring the backing memory remains live until
 * the last view is closed.
 *
 * <h2>Reference-Count Lifecycle</h2>
 * <pre>
 *  allocate()     → refCount = 1
 *  retain()       → refCount++
 *  slice()/view() → refCount++ (on parent)
 *  close()        → refCount--; if (refCount == 0) → return to pool
 * </pre>
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * try (LoanedBuffer buf = allocator.allocate(AllocationHint.MEDIUM)) {
 *     buf.segment().set(ValueLayout.JAVA_BYTE, 0, (byte) 0xFF);
 *     transport.send(buf);   // transport calls buf.retain()
 * }
 * // close() called here; if transport still holds a retain, memory lives on
 * }</pre>
 *
 * <h2>Packet Fragmentation (Zero-Copy)</h2>
 * <pre>{@code
 * // Split a 64 KB frame into two 32 KB halves WITHOUT copying:
 * try (LoanedBuffer frame = allocator.allocateNetwork(65_536)) {
 *     try (LoanedBuffer head = frame.slice(0, 32_768);
 *          LoanedBuffer tail = frame.slice(32_768, 32_768)) {
 *         pipeline.sendFragment(head);
 *         pipeline.sendFragment(tail);
 *     }
 * }
 * }</pre>
 *
 * @since 0.5.0
 * @see MemoryAllocator
 */
public interface LoanedBuffer extends AutoCloseable {

    // =========================================================================
    // Core segment access
    // =========================================================================

    /**
     * Returns the underlying Panama FFM {@link MemorySegment}.
     *
     * <p>Callers use {@code MemorySegment.set(ValueLayout, offset, value)} for direct
     * reads and writes — no intermediate arrays, no copies.
     *
     * @return live segment (off-heap or on-heap depending on the active allocator tier)
     * @throws IllegalStateException if this buffer has already been closed
     */
    MemorySegment segment();

    /**
     * Number of bytes currently written into this buffer (the logical write-cursor).
     *
     * @return value in {@code [0, capacity()]}
     */
    long size();

    /**
     * Maximum number of bytes this buffer can hold.
     *
     * @return backing segment byte size
     */
    long capacity();

    // =========================================================================
    // Zero-copy fragmentation
    // =========================================================================

    /**
     * Returns a <em>zero-copy view</em> of this buffer, starting at {@code offset} for
     * {@code length} bytes.
     *
     * <p><strong>Contract:</strong>
     * <ul>
     *   <li>Returns a new {@code LoanedBuffer} whose {@code segment()} is
     *       {@code this.segment().asSlice(offset, length)} — no bytes are copied.</li>
     *   <li>This buffer's reference count is incremented by one. The slice MUST
     *       be closed by the caller; when it is closed the parent's refCount is
     *       decremented.</li>
     *   <li>The slice's own {@code size()} equals {@code length}.</li>
     * </ul>
     *
     * @param offset byte offset within this buffer (0-based)
     * @param length number of bytes in the slice
     * @return a zero-copy slice; caller must close it
     * @throws IndexOutOfBoundsException if {@code offset + length > capacity()}
     * @throws IllegalStateException     if this buffer is closed
     */
    LoanedBuffer slice(long offset, long length);

    /**
     * Returns a <em>read-only zero-copy view</em> of this buffer covering all valid data.
     *
     * <p>Equivalent to {@code slice(0, size())} but semantically signals read-only
     * intent. Implementations MAY return a read-only {@link MemorySegment} to prevent
     * accidental mutation by downstream handlers.
     *
     * @return read-only view of current data; caller must close it
     * @throws IllegalStateException if this buffer is closed
     */
    LoanedBuffer view();

    /**
     * Returns a <em>zero-copy view</em> of this buffer covering a specific range.
     *
     * <p>Unlike {@link #slice}, this method does <strong>not</strong> increment the
     * parent refCount — the caller must ensure the parent stays alive for the duration
     * of use. Suitable for single-threaded pipelines where lifetime is clear.
     *
     * <p><b>Warning:</b> prefer {@link #slice} when the view will be shared across
     * thread boundaries. Use {@code peek} only in strictly same-scope, same-thread paths.
     *
     * @param offset byte offset within this buffer
     * @param length number of bytes
     * @return a lightweight, non-owning view
     * @throws IndexOutOfBoundsException if {@code offset + length > capacity()}
     * @throws IllegalStateException     if this buffer is closed
     */
    LoanedBuffer peek(long offset, long length);

    // =========================================================================
    // Reference counting
    // =========================================================================

    /**
     * Increments the reference count (claims shared ownership).
     *
     * <p>Call this before passing the buffer to another component that will
     * independently manage its lifetime (e.g., async transport callback).
     *
     * @throws IllegalStateException if the buffer has already been fully released
     */
    void retain();

    /**
     * Decrements the reference count. When it reaches zero the buffer is returned
     * to the pool or the backing {@code MemorySegment} is released.
     *
     * <p>Must be idempotent: multiple calls to {@code close()} on an already-released
     * buffer must not throw.
     */
    @Override
    void close();

    /**
     * Returns the current reference count (for diagnostics and tests only).
     *
     * @return reference count; {@code 0} means the buffer has been returned to the pool
     */
    int refCount();

    // =========================================================================
    // Size management
    // =========================================================================

    /**
     * Sets the logical write-cursor (number of valid bytes).
     *
     * @param newSize new size in bytes; must satisfy {@code 0 <= newSize <= capacity()}
     * @throws IllegalArgumentException if {@code newSize} is out of range
     * @throws IllegalStateException    if this buffer is closed
     */
    void setSize(long newSize);

    // =========================================================================
    // Liveness
    // =========================================================================

    /**
     * Returns {@code true} if this buffer is still live (refCount &gt; 0).
     *
     * @return buffer liveness
     */
    boolean isAlive();

    /**
     * Registers a cleanup action to be invoked when the reference count reaches zero.
     * Actions are executed in LIFO order.
     *
     * @param action cleanup callback; must not throw
     */
    void addCloseAction(Runnable action);
}

