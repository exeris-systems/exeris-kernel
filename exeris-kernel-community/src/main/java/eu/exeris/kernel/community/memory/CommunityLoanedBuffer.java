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
 * Community: Off-heap {@link eu.exeris.kernel.spi.memory.LoanedBuffer} backed by shared
 * or per-buffer {@link Arena} depending on allocation source.
 *
 * <h2>Memory Model (Community Tier)</h2>
 * <p>Buffers allocated via {@link CommunityMemoryAllocator} are sourced from the
 * {@code CommunityArenaShardPool}: size-class buckets (512B, 4K, 16K, 32K, 64K, 128K,
 * 256K, 512K, 1M) from shard-local shared arenas, with reusable free queues to
 * reduce per-allocation contention. Payloads above 1 MB bypass free-list reuse,
 * but are still allocated from pool-owned shard arenas.
 *
 * <p>On buffer release, if the buffer came from the pool, it is returned to the pool's
 * free queue for reuse, using the original shard where it came from. If the pool is
 * closed, returned buffers are discarded.
 *
 * <h2>Zero-Copy</h2>
 * <p>The backing {@link MemorySegment} is never copied. Slices use
 * {@code MemorySegment.asSlice()} on the same native region.
 *
 * @since 0.5.0
 * @see CommunityMemoryAllocator
 * @see CommunityArenaShardPool
 */
final class CommunityLoanedBuffer extends AbstractLoanedBuffer {

    private final MemorySegment segment;
    private final CommunityArenaShardPool pool;
    private final long originalCapacityBytes;
    private final int originShard;
    private final CommunityReleaseAccounting releaseAccounting;

    // =========================================================================
    // Constructor — DeclarationOrder: fields → constructor → static factories
    // =========================================================================

    private CommunityLoanedBuffer(MemorySegment segment) {
        super();
        this.segment = segment;
        this.pool = null;
        this.originalCapacityBytes = 0L;
        this.originShard = 0;
        this.releaseAccounting = null;
    }

    private CommunityLoanedBuffer(
            MemorySegment segment,
            long originalCapacityBytes,
            int originShard,
            CommunityArenaShardPool pool,
            CommunityReleaseAccounting releaseAccounting
    ) {
        super();
        this.segment = segment;
        this.originalCapacityBytes = originalCapacityBytes;
        this.originShard = originShard;
        this.pool = pool;
        this.releaseAccounting = releaseAccounting;
    }

    // =========================================================================
    // Static factory methods
    // =========================================================================

    /**
     * Creates a buffer sourced from the provided pool.
     * On release, the buffer (segment) is returned to the pool's free queue.
     *
     * @param segment              the allocated memory segment
     * @param originalCapacityBytes the original requested capacity (for pool lookup and release accounting)
     * @param originShard          shard that originally supplied the segment
     * @param pool                 the arena shard pool managing reuse
     * @param releaseAccounting    the allocator's shared release-accounting helper, invoked on release
     */
    /* default */ static CommunityLoanedBuffer allocateOwnedPooled(
            MemorySegment segment,
            long originalCapacityBytes,
            int originShard,
            CommunityArenaShardPool pool,
            CommunityReleaseAccounting releaseAccounting
    ) {
        return new CommunityLoanedBuffer(segment, originalCapacityBytes, originShard, pool, releaseAccounting);
    }

    /**
     * Creates a buffer that <em>logically owns</em> the supplied arena.
      * Community currently tracks release logically and no explicit arena close
      * is performed in {@link #onRelease()}.
     *
     * @param capacityBytes capacity in bytes
     * @param alignment     byte alignment
     * @param ownedArena    arena whose lifecycle is associated with this buffer
     */
    /* default */ static CommunityLoanedBuffer allocateOwned(
            long capacityBytes, long alignment, Arena ownedArena) {
        MemorySegment seg = ownedArena.allocate(capacityBytes, alignment);
        return new CommunityLoanedBuffer(seg);
    }

    @Override
    protected MemorySegment backingSegment() {
        return segment;
    }

    @Override
    protected void onRelease() {
        // Single dispatch guaranteed by refcount CAS — see AbstractLoanedBuffer.onRelease(). Release
        // accounting is folded in here (formerly a per-buffer close-action) using this buffer's own
        // capacity — the same value the matching trackAllocation added, so allocatedBytes stays balanced.
        if (releaseAccounting != null) {
            releaseAccounting.release(originalCapacityBytes);
        }
        if (pool != null) {
            pool.returnSegment(originalCapacityBytes, originShard, segment);
        }
    }
}
