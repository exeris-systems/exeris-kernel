/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.spi.memory;

import java.lang.foreign.MemorySegment;

/**
 * SPI: A borrowed, reference-counted buffer backed by a {@link MemorySegment}.
 *
 * <h2>Zero-Copy Contract</h2>
 * <p>Data is never copied between heap and off-heap. All fragment operations
 * ({@link #slice}, {@link #view}, {@link #peek}) return views into the <em>same</em> underlying
 * {@link MemorySegment} — zero bytes are moved. The parent buffer's reference count
 * is incremented by each owning view or slice, so the backing memory remains live until
 * the last of them is closed.
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
 * {@snippet lang="java" :
 * try (LoanedBuffer buf = allocator.allocate(AllocationHint.MEDIUM)) {
 *     buf.segment().set(ValueLayout.JAVA_BYTE, 0, (byte) 0xFF);
 *     transport.send(buf);   // transport calls buf.retain()
 * }
 * // close() runs here; while transport holds its retain, the memory stays live
 * }
 *
 * <h2>Packet Fragmentation (Zero-Copy)</h2>
 * {@snippet lang="java" :
 * // Split a 64 KB frame into two 32 KB halves without copying:
 * try (LoanedBuffer frame = allocator.allocateNetwork(65_536)) {
 *     try (LoanedBuffer head = frame.slice(0, 32_768);
 *          LoanedBuffer tail = frame.slice(32_768, 32_768)) {
 *         pipeline.sendFragment(head);
 *         pipeline.sendFragment(tail);
 *     }
 * }
 * }
 *
 * <p><b>Allocation:</b> zero-alloc on hot path — {@link #slice}, {@link #view} and
 * {@link #peek} move no bytes and add nothing beyond the returned handle; pooling of the
 * backing segment is the allocator's concern.
 * <p><b>Thread confinement:</b> virtual-thread-safe — the reference count is maintained
 * atomically, so a buffer may cross a thread boundary; the hand-off must be paired,
 * {@link #retain()} before publishing the reference and {@link #close()} by the receiver.
 * <p><b>Ownership:</b> the holder of the last reference releases the memory via
 * {@link #close()}; every {@code slice} and {@code view} owns a reference of its own and
 * must be closed, while a {@code peek} view owns nothing and must not outlive its parent.
 *
 * @implSpec Implementations must return the backing segment to the pool exactly once, on
 *           the transition from reference count 1 to 0, and must reject every subsequent
 *           operation on the buffer with {@code IllegalStateException}. {@link #close()}
 *           on an already-released buffer is the one exception: it is a no-op.
 * @apiNote Prefer {@code try-with-resources}. A missed {@code close()} is a silent leak
 *          that only {@link LeakDetectionMode#SAMPLED} or {@link LeakDetectionMode#PARANOID}
 *          will report, as {@code EX-MEM-1002}.
 * @since 0.5
 * @see MemoryAllocator
 */
public interface LoanedBuffer extends AutoCloseable {

    // =========================================================================
    // Core segment access
    // =========================================================================

    /**
     * Returns the backing memory as a live Panama FFM {@link MemorySegment} whose validity
     * ends when this buffer's reference count reaches zero.
     *
     * <p>Callers read and write through {@code MemorySegment.get}/{@code set(ValueLayout,
     * offset, value)} — no intermediate arrays, no copies. The segment spans
     * {@link #capacity()} bytes, of which the first {@link #size()} hold valid data.
     * A segment obtained from a buffer returned by {@link #view()} may be read-only.
     *
     * @return live segment (off-heap or on-heap depending on the active allocator tier)
     * @throws IllegalStateException if this buffer has already been closed
     */
    MemorySegment segment();

    /**
     * Returns the number of valid data bytes in this buffer — the logical write-cursor,
     * which is what a reader must respect and is independent of {@link #capacity()}.
     *
     * @return value in {@code [0, capacity()]}
     */
    long size();

    /**
     * Returns the byte size of the backing segment — the hard upper bound for
     * {@link #setSize} and for any write through {@link #segment()}.
     *
     * <p>May exceed the {@link AllocationHint#sizeBytes()} that was requested, because the
     * allocator rounds to a pool bucket.
     *
     * @return backing segment byte size
     */
    long capacity();

    // =========================================================================
    // Zero-copy fragmentation
    // =========================================================================

    /**
     * Returns an owning, zero-copy view of {@code length} bytes of this buffer starting at
     * {@code offset}, which keeps the parent's memory live until the slice is closed.
     *
     * <p>The slice shares the parent's backing segment — no bytes are copied — but it is an
     * independent {@code LoanedBuffer} with a {@link #close()} obligation of its own, and it
     * is therefore the form to use when the fragment may cross a thread boundary.
     *
     * @param offset byte offset within this buffer (0-based)
     * @param length number of bytes in the slice
     * @return a zero-copy slice whose own {@code size()} equals {@code length}; the caller
     *         must close it
     * @throws IndexOutOfBoundsException if {@code offset + length > capacity()}
     * @throws IllegalStateException     if this buffer is closed
     * @implSpec The returned buffer's {@code segment()} must be
     *           {@code this.segment().asSlice(offset, length)}, and this buffer's reference
     *           count must be incremented by one on the call and decremented when the slice
     *           is closed.
     */
    LoanedBuffer slice(long offset, long length);

    /**
     * Returns an owning, zero-copy view over the {@link #size()} valid bytes of this buffer,
     * signalling read-only intent to downstream handlers.
     *
     * <p>Equivalent in extent to {@code slice(0, size())}. Implementations may back it with a
     * read-only {@link MemorySegment} to prevent accidental mutation of the parent's bytes.
     *
     * @return read-only view of the current data; the caller must close it
     * @throws IllegalStateException if this buffer is closed
     * @implSpec This buffer's reference count must be incremented by one on the call and
     *           decremented when the view is closed, exactly as for {@link #slice}.
     */
    LoanedBuffer view();

    /**
     * Returns a zero-copy, <em>non-owning</em> view of a range of this buffer: it takes no
     * reference, so the caller must keep this buffer alive for as long as the view is used.
     *
     * <p>Unlike {@link #slice}, which takes a reference and is therefore safe across thread
     * boundaries, {@code peek} is for same-scope, same-thread reads where the parent buffer's
     * lifetime trivially covers the view's use.
     *
     * @param offset byte offset within this buffer
     * @param length number of bytes
     * @return a lightweight, non-owning view; {@code close()} on it is a no-op for
     *         reference counting
     * @throws IndexOutOfBoundsException if {@code offset + length > capacity()}
     * @throws IllegalStateException     if this buffer is closed
     * @implSpec Implementations must not increment this buffer's reference count for the
     *           returned view. On that view, {@link #close()} must be a no-op that does not
     *           decrement the parent's count, {@link #retain()} must be a no-op that
     *           increments no counter, and {@link #refCount()} is diagnostic only — it should
     *           report the parent's count and must not maintain an independent mutable count.
     * @apiNote Prefer {@link #slice} whenever the view may cross a thread boundary, or
     *          whenever the ownership boundaries are not immediately obvious at the call
     *          site: a peek view that outlives its parent is a use-after-free on the
     *          native segment.
     * @implNote The kernel reports a {@link #retain()} or {@link #addCloseAction} call on a
     *           peek view as {@code EX-MEM-1003} (peek-view ownership misuse).
     */
    LoanedBuffer peek(long offset, long length);

    // =========================================================================
    // Reference counting
    // =========================================================================

    /**
     * Claims a share of ownership by incrementing the reference count, so that the memory
     * outlives the caller's own {@link #close()}.
     *
     * @throws IllegalStateException if the buffer has already been fully released
     * @apiNote Call this <em>before</em> publishing the buffer to a component that manages
     *          its lifetime independently — an async transport callback, a forked subtask,
     *          a queue crossing to another virtual thread. The receiver closes the reference
     *          this call created; the retain and that close must pair exactly.
     */
    void retain();

    /**
     * Releases one reference; when the last one goes, the buffer is returned to the pool or
     * the backing {@code MemorySegment} is freed and the registered close actions run.
     *
     * @implSpec Must be idempotent: further calls to {@code close()} on an already-released
     *           buffer must not throw and must not decrement any count.
     */
    @Override
    void close();

    /**
     * Returns how many references are currently outstanding, for diagnostics and tests.
     *
     * @return reference count; {@code 0} means the buffer has been returned to the pool
     * @apiNote Never branch production logic on this value — it can change under a
     *          concurrent hand-off between the read and the branch. Pair {@link #retain()}
     *          with {@link #close()} instead.
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
     * Reports whether the backing memory is still valid — that is, whether at least one
     * reference is outstanding.
     *
     * @return {@code true} while {@code refCount() > 0}; {@code false} once the buffer has
     *         been returned to the pool, after which every accessor throws
     *         {@code IllegalStateException}
     */
    boolean isAlive();

    /**
     * Registers a cleanup action to run when the reference count reaches zero, immediately
     * before the memory is returned to the pool.
     *
     * <p>Actions are executed in LIFO order, so an action registered later sees the state
     * left by the ones registered before it.
     *
     * @param action cleanup callback; must not throw
     * @implNote The kernel's Core base implementation holds a fixed set of four action slots
     *           per buffer and rejects a fifth registration; a peek view holds none at all
     *           (see {@link #peek}).
     */
    void addCloseAction(Runnable action);
}
