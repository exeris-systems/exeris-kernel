/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.community.http;

import eu.exeris.kernel.spi.memory.LoanedBuffer;
import eu.exeris.kernel.spi.memory.MemoryAllocator;

import java.io.OutputStream;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.Objects;

/**
 * An {@link OutputStream} that writes directly into a kernel-loaned off-heap
 * {@link MemorySegment}, growing to a larger {@link LoanedBuffer} on overflow.
 *
 * <p>Purpose: let a serializer stream bytes <em>straight into</em> a network-registered
 * segment (e.g. {@code ObjectMapper.writeValue(OutputStream, ...)}), eliminating both the
 * intermediate heap {@code byte[]} and the heap&rarr;off-heap {@code MemorySegment.copy}
 * that a materialize-then-copy path pays on every response (No-Waste-Compute). The
 * {@code java.io.OutputStream} surface here is the serializer bridge only — no heap
 * buffering happens; each write lands in the off-heap segment.
 *
 * <h2>Ownership (unforgiving — off-heap)</h2>
 * <p>This sink owns exactly one live buffer at a time ({@link #buffer()}). On growth it
 * allocates the replacement, copies the bytes written so far, then closes the old buffer —
 * so at most one buffer is ever live. It deliberately does <strong>not</strong> release the
 * current buffer on {@link #close()} (a serializer may auto-close its output target): the
 * caller owns the terminal decision — {@link #setSizeToWritten()} then take {@link #buffer()}
 * on success (ownership transfers to the caller), or {@link #discard()} on any failure.
 */
final class SegmentSink extends OutputStream {

    private final MemoryAllocator allocator;
    private LoanedBuffer buffer;
    private MemorySegment segment;
    private long written;

    /* default */ SegmentSink(LoanedBuffer initial, MemoryAllocator allocator) {
        super();
        this.allocator = Objects.requireNonNull(allocator, "allocator must not be null");
        this.buffer = Objects.requireNonNull(initial, "initial must not be null");
        this.segment = initial.segment();
    }

    @Override
    public void write(int value) {
        ensureCapacity(written + 1L);
        segment.set(ValueLayout.JAVA_BYTE, written, (byte) value);
        written++;
    }

    @Override
    public void write(byte[] data, int off, int len) {
        Objects.checkFromIndexSize(off, len, data.length);
        if (len == 0) {
            return;
        }
        ensureCapacity(written + len);
        MemorySegment.copy(MemorySegment.ofArray(data), off, segment, written, len);
        written += len;
    }

    /** No-op: the buffer lifecycle is the caller's decision, not a serializer auto-closing its target. */
    @Override
    public void close() {
        // intentionally empty — see class Javadoc "Ownership".
    }

    /**
     * The current live buffer. Ownership transfers to the caller only after
     * {@link #setSizeToWritten()}; before that, the sink still owns it.
     *
     * @return the buffer currently backing this sink
     */
    /* default */ LoanedBuffer buffer() {
        return buffer;
    }

    /**
     * Number of bytes written into the buffer so far.
     *
     * @return the write cursor in {@code [0, capacity()]}
     */
    /* default */ long written() {
        return written;
    }

    /** Stamps the current buffer's logical size to the bytes written; call before handing it off. */
    /* default */ void setSizeToWritten() {
        buffer.setSize(written);
    }

    /** Releases the current buffer; call on the failure path so no off-heap memory leaks. */
    /* default */ void discard() {
        buffer.close();
    }

    // CloseResource: `grown` ownership is either transferred to `buffer` or closed on the failure path.
    // AvoidCatchingGenericException: release the freshly-allocated replacement if the copy fails before
    // the ownership swap, so a copy fault (impossible under our sizing invariant, but PARANOID-safe) leaks nothing.
    @SuppressWarnings({"PMD.CloseResource", "PMD.AvoidCatchingGenericException"})
    private void ensureCapacity(long need) {
        if (need <= buffer.capacity()) {
            return;
        }
        if (need > Integer.MAX_VALUE) {
            throw new IllegalStateException("JSON response exceeds maximum encodable size: " + need);
        }
        long target = Math.max(need, buffer.capacity() * 2L);
        int newCapacity = (int) Math.min(target, Integer.MAX_VALUE);
        // Allocate first: if this throws, the old buffer is untouched and the caller discards it.
        LoanedBuffer grown = allocator.allocateNetwork(newCapacity);
        try {
            MemorySegment.copy(segment, 0L, grown.segment(), 0L, written);
        } catch (RuntimeException e) {
            grown.close();
            throw e;
        }
        buffer.close();
        buffer = grown;
        segment = grown.segment();
    }
}
