/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.community.memory;

import eu.exeris.kernel.core.memory.AbstractLoanedBuffer;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;

/**
 * Community: Off-heap {@link eu.exeris.kernel.spi.memory.LoanedBuffer} backed by a
 * shared, per-buffer {@link Arena}.
 *
 * <h2>Memory Model (Community Tier)</h2>
 * <p>Each buffer owns the {@link Arena} it was allocated from via {@link #allocateOwned}.
 * When the buffer's reference count drops to zero, {@link #onRelease()} closes the arena,
 * returning the off-heap memory to the OS. This is "syscall-based" allocation — no global
 * pool.
 *
 * <h2>Zero-Copy</h2>
 * <p>The backing {@link MemorySegment} is never copied. Slices use
 * {@code MemorySegment.asSlice()} on the same native region.
 *
 * @since 0.5.0
 * @see CommunityMemoryAllocator
 */
final class CommunityLoanedBuffer extends AbstractLoanedBuffer {

    private final MemorySegment segment;
    private final Arena arena;

    // =========================================================================
    // Constructor — DeclarationOrder: fields → constructor → static factories
    // =========================================================================

    private CommunityLoanedBuffer(MemorySegment segment, Arena arena) {
        super();
        this.segment = segment;
        this.arena   = arena;
    }

    // =========================================================================
    // Static factory methods
    // =========================================================================

    /**
     * Creates a buffer that <em>owns</em> the supplied arena.
     * {@link #onRelease()} will close the arena when the reference count reaches zero.
     *
     * @param capacityBytes capacity in bytes
     * @param alignment     byte alignment
     * @param ownedArena    arena whose lifecycle is transferred to this buffer
     */
    /* default */ static CommunityLoanedBuffer allocateOwned(
            long capacityBytes, long alignment, Arena ownedArena) {
        MemorySegment seg = ownedArena.allocate(capacityBytes, alignment);
        return new CommunityLoanedBuffer(seg, ownedArena);
    }

    @Override
    protected MemorySegment backingSegment() {
        return segment;
    }

    @Override
    protected void onRelease() {
        try {
            arena.close();
        } catch (IllegalStateException _) {
            // Arena already closed (e.g., connection scope exited) — safe to ignore
        }
    }
}
