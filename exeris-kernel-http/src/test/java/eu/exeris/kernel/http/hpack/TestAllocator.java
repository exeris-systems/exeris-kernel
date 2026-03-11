/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.http.hpack;

import eu.exeris.kernel.spi.memory.AllocationHint;
import eu.exeris.kernel.spi.memory.LoanedBuffer;
import eu.exeris.kernel.spi.memory.MemoryAllocator;
import eu.exeris.kernel.spi.memory.MemoryStats;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;

/**
 * Test-scope {@link MemoryAllocator} backed by a shared {@link Arena}.
 *
 * <p>Each {@link #allocate} call returns a minimal {@link LoanedBuffer} stub whose
 * {@link LoanedBuffer#close()} is a no-op (the arena is closed externally).
 * This allocator is intended exclusively for unit/integration tests in the HTTP module.
 */
public final class TestAllocator implements MemoryAllocator {

    private final Arena arena;

    public TestAllocator(Arena arena) {
        this.arena = arena;
    }

    @Override
    public LoanedBuffer allocate(AllocationHint hint) {
        return bufferOf(arena.allocate(hint.sizeBytes(), 8L));
    }

    @Override
    public LoanedBuffer allocateNetwork(int estimatedBytes) {
        return bufferOf(arena.allocate(estimatedBytes, 8L));
    }

    @Override
    public LoanedBuffer allocateCarrierSlab(int carrierIndex) {
        return allocate(AllocationHint.SMALL);
    }

    @Override
    public LoanedBuffer allocateInfrastructure(long sizeBytes) {
        return bufferOf(arena.allocate(sizeBytes, 8L));
    }

    @Override
    public MemoryStats stats() {
        return MemoryStats.zero();
    }

    @Override
    public void close() {
        // arena lifecycle managed externally
    }

    private static LoanedBuffer bufferOf(MemorySegment seg) {
        return new StubLoanedBuffer(seg);
    }

    private static final class StubLoanedBuffer implements LoanedBuffer {
        private final MemorySegment seg;
        private long sz;

        StubLoanedBuffer(MemorySegment seg) {
            this.seg = seg;
            this.sz = seg.byteSize();
        }

        @Override public MemorySegment segment()       { return seg; }
        @Override public long size()                    { return sz; }
        @Override public long capacity()                { return seg.byteSize(); }
        @Override public void setSize(long newSize)     { sz = newSize; }
        @Override public void retain()                  { /* stub */ }
        @Override public int  refCount()                { return 1; }
        @Override public boolean isAlive()              { return true; }
        @Override public void addCloseAction(Runnable r) { /* stub */ }

        @Override
        public LoanedBuffer slice(long off, long len) {
            return new StubLoanedBuffer(seg.asSlice(off, len));
        }

        @Override
        public LoanedBuffer view() { return this; }

        @Override
        public LoanedBuffer peek(long off, long len) {
            return new StubLoanedBuffer(seg.asSlice(off, len));
        }

        @Override
        public void close() { /* arena-owned — no-op */ }
    }
}