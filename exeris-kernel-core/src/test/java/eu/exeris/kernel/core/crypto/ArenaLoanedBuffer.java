/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
 package eu.exeris.kernel.core.crypto;

import eu.exeris.kernel.spi.memory.AllocationHint;
import eu.exeris.kernel.spi.memory.LoanedBuffer;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.util.ArrayList;
import java.util.List;

/**
 * Test-scope {@link LoanedBuffer} backed by a {@link Arena}-allocated {@link MemorySegment}.
 *
 * <h2>Rationale</h2>
 * <p>Eliminates verbatim duplication of a 12-method anonymous {@code LoanedBuffer}
 * across {@code OffHeapTlsEngineTest}, {@code OffHeapTlsEngineLoopbackIT}, and
 * {@code NativeCipherContextTest}. Each test allocates via
 * {@link #allocate(Arena, AllocationHint, int)} and the buffer's {@link #close()} fires
 * registered close-actions (deferred semantics matching the production contract).
 *
 * <h2>Lifecycle</h2>
 * <p>The backing {@link Arena} is owned by the caller — this buffer does NOT close
 * the arena on {@link #close()}. Use
 * {@link #allocateOwning(AllocationHint, int)} when the buffer should own and close
 * its own {@code Arena.ofShared()} instance.
 *
 * <p>This class is {@code public} for test-scope cross-package access
 * (e.g. {@code eu.exeris.kernel.core.crypto.tls}, {@code .openssl}).
 * It is <strong>not</strong> part of the production SPI and must never be
 * referenced from main source sets.
 */
public final class ArenaLoanedBuffer implements LoanedBuffer {

    private final MemorySegment segment;
    private final int capacity;
    private final Runnable onClose;
    private final List<Runnable> closeActions = new ArrayList<>();
    private long sz;

    private ArenaLoanedBuffer(MemorySegment segment, int capacity, Runnable onClose) {
        this.segment  = segment;
        this.capacity = capacity;
        this.sz       = capacity;
        this.onClose  = onClose;
    }

    /**
     * Allocates a buffer from the given arena. The caller is responsible for closing the arena.
     *
     * @param arena  arena to allocate from (caller-owned)
     * @param hint   sizing hint
     * @param minBytes minimum allocation size (floor for hint.sizeBytes())
     */
    public static ArenaLoanedBuffer allocate(Arena arena, AllocationHint hint, int minBytes) {
        int bytes = Math.max(hint.sizeBytes(), minBytes);
        MemorySegment seg = arena.allocate(bytes, 8L);
        return new ArenaLoanedBuffer(seg, bytes, () -> { /* arena owned by caller */ });
    }

    /**
     * Allocates a buffer that owns its own {@code Arena.ofShared()} instance.
     * {@link #close()} will close the arena after firing registered close-actions.
     *
     * @param hint     sizing hint
     * @param minBytes minimum allocation size
     */
    public static ArenaLoanedBuffer allocateOwning(AllocationHint hint, int minBytes) {
        int bytes = Math.max(hint.sizeBytes(), minBytes);
        Arena arena = Arena.ofShared(); //NOPMD DirectArena — test harness only
        MemorySegment seg = arena.allocate(bytes, 8L);
        return new ArenaLoanedBuffer(seg, bytes, arena::close);
    }

    @Override public MemorySegment segment()                      { return segment; }
    @Override public long           size()                        { return sz; }
    @Override public long           capacity()                    { return capacity; }
    @Override public void           setSize(long s)               { sz = s; }
    @Override public void           retain()                      { /* test stub — no ref-count */ }
    @Override public int            refCount()                    { return 1; }
    @Override public boolean        isAlive()                     { return true; }
    @Override public void           addCloseAction(Runnable r)    { closeActions.add(r); }

    @Override
    public LoanedBuffer slice(long offset, long len) {
        throw new UnsupportedOperationException("ArenaLoanedBuffer test stub does not implement slice()");
    }

    @Override
    public LoanedBuffer view() {
        throw new UnsupportedOperationException("ArenaLoanedBuffer test stub does not implement view()");
    }

    @Override
    public LoanedBuffer peek(long offset, long len) {
        throw new UnsupportedOperationException("ArenaLoanedBuffer test stub does not implement peek()");
    }

    @Override
    public void close() {
        closeActions.forEach(Runnable::run);
        onClose.run();
    }
}
