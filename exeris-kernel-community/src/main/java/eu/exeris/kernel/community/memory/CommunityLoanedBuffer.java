/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
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
 * <p><b>Allocation:</b> {@link #allocateOwnedPooled} wraps a segment the caller already
 * allocated from a {@link CommunityArenaShardPool}, allocating no native memory itself;
 * {@link #allocateOwned(long, long, Arena)} allocates the segment directly from the
 * supplied {@link Arena}.
 * <p><b>Thread confinement:</b> none of its own — reference-count transitions are
 * CAS-based in {@link AbstractLoanedBuffer}, so a buffer may be retained, released or
 * sliced from any thread.
 * <p><b>Ownership:</b> on the 1-to-0 reference-count transition ({@link #onRelease()}), a
 * pool-sourced buffer is handed back to its {@link CommunityArenaShardPool} shard (or,
 * above the pool's largest size class, only accounted for — its backing memory is
 * reclaimed when the pool itself closes) and its {@link CommunityReleaseAccounting} is
 * decremented; a buffer created via {@link #allocateOwned(long, long, Arena)} performs no
 * release action at all — the caller-supplied {@link Arena}'s lifecycle is never managed
 * by this class.
 *
 * @since 0.5
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
     * On release, the segment is handed back to {@code pool} via {@link
     * CommunityArenaShardPool#returnSegment}, which adds it to the origin shard's free
     * queue when its capacity fits a size class, or only accounts for it otherwise.
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
     * Creates a buffer wrapping a segment allocated directly from {@code ownedArena}.
     * The returned buffer takes no ownership of {@code ownedArena}: its {@link #onRelease()}
     * performs no release action, and the arena's lifecycle remains the caller's responsibility.
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

    /**
     * Returns this buffer's backing {@link MemorySegment} — the same segment supplied at
     * construction, whether it was sourced from a {@link CommunityArenaShardPool} shard or
     * allocated directly from a caller-supplied {@link Arena}.
     *
     * @return the backing segment
     */
    @Override
    protected MemorySegment backingSegment() {
        return segment;
    }

    /**
     * Releases this buffer's backing storage on the 1-to-0 reference-count transition.
     *
     * <p>Decrements the shared {@link CommunityReleaseAccounting} by this buffer's original
     * capacity when one was supplied at construction, then — if this buffer was sourced from
     * a {@link CommunityArenaShardPool} — hands the segment back to that pool's free queue for
     * the shard it originally came from. A buffer constructed via
     * {@link #allocateOwned(long, long, Arena)} has neither reference and does nothing here;
     * the caller-supplied {@link Arena} is left open.
     */
    @Override
    protected void onRelease() {
        // Single dispatch is guaranteed by the refcount CAS in AbstractLoanedBuffer.close(); no
        // idempotency guard is needed here. Release accounting uses this buffer's own capacity —
        // the same value the matching trackAllocation() call added — so allocatedBytes stays
        // balanced.
        if (releaseAccounting != null) {
            releaseAccounting.release(originalCapacityBytes);
        }
        if (pool != null) {
            pool.returnSegment(originalCapacityBytes, originShard, segment);
        }
    }
}
