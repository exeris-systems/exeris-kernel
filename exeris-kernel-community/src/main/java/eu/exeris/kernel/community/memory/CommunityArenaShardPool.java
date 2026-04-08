/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.community.memory;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReferenceArray;

/**
 * Community: Shard-based arena pool to reduce per-allocation overhead and enforce
 * deterministic shared arena semantics. Manages one shared {@code Arena} per shard
 * with size-class buckets and intrinsic free queues.
 *
 * <h2>Design</h2>
 * <p>Allocations map to one of nine size classes (512, 4K, 16K, 32K, 64K, 128K,
 * 256K, 512K, 1M).
 * Each shard maintains a lock-free queue of unreturned buffers. On allocation,
 * the pool attempts to reuse a buffer from the queue; on release, the buffer
 * is returned to the queue for future reuse. <strong>All allocations, including
 * those exceeding 1 MB, now use the shared arena:</strong> this enforces deterministic
 * release semantics across LoanedBuffer hand-offs (e.g., queue-based cross-VT
 * transfer).
 *
 * <h2>Ownership</h2>
 * <p>The pool owns shard arenas and all buffers allocated from them. On pool close,
 * shard arenas are closed in deterministic order, invalidating outstanding buffers.
 * Subsequent allocations throw IllegalStateException.
 *
 * @since 0.5.0
 */
@SuppressWarnings({"PMD.CloseResource", "PMD.CyclomaticComplexity"})
final class CommunityArenaShardPool {

    private static final int SIZE_512B = 512;
    private static final int SIZE_4K = 4 * 1_024;
    private static final int SIZE_16K = 16 * 1_024;
    private static final int SIZE_32K = 32 * 1_024;
    private static final int SIZE_64K = 64 * 1_024;
    private static final int SIZE_128K = 128 * 1_024;
    private static final int SIZE_256K = 256 * 1_024;
    private static final int SIZE_512K = 512 * 1_024;
    private static final int SIZE_1M = 1_024 * 1_024;

    private static final int[] SIZE_CLASSES = {
        SIZE_512B,
        SIZE_4K,
        SIZE_16K,
        SIZE_32K,
        SIZE_64K,
        SIZE_128K,
        SIZE_256K,
        SIZE_512K,
        SIZE_1M
    };

    private static final int[] SIZE_CLASS_CAPACITIES = {
        128, 128, 128, 128, 128, 128, 8, 4, 2
    };
    private static final int SHARD_COUNT = clampPowerOfTwo(
            Runtime.getRuntime().availableProcessors() * 2,
            4,
            32
    );
    private static final String POOL_CLOSED_MESSAGE = "Pool has been closed";

    private final AtomicReferenceArray<Arena> shardArenas = new AtomicReferenceArray<>(SHARD_COUNT);
    private final Object[] shardInitLocks = new Object[SHARD_COUNT];
    private final Bucket[][] freeLists;
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final AtomicLong overflowReturnCount = new AtomicLong(0);
    private final AtomicLong overflowReturnedBytes = new AtomicLong(0);

    /* default */ CommunityArenaShardPool() {
        Bucket[][] buckets = new Bucket[SIZE_CLASSES.length][SHARD_COUNT];
        for (int classIndex = 0; classIndex < SIZE_CLASSES.length; classIndex++) {
            for (int shardIndex = 0; shardIndex < SHARD_COUNT; shardIndex++) {
                buckets[classIndex][shardIndex] = new Bucket();
            }
        }
        this.freeLists = buckets;
        for (int shardIndex = 0; shardIndex < SHARD_COUNT; shardIndex++) {
            shardInitLocks[shardIndex] = new Object();
        }
    }

    /* default */ Allocation allocateSegment(long capacityBytes, long alignment) {
        if (closed.get()) {
            throw new IllegalStateException(POOL_CLOSED_MESSAGE);
        }

        int shardMask = SHARD_COUNT - 1;
        int localShard = Long.hashCode(Thread.currentThread().threadId()) & shardMask;

        int classIndex = getSizeClassIndex(capacityBytes);
        if (classIndex < 0) {
            return allocateFromShardOverflow(localShard, capacityBytes, alignment);
        }

        Bucket[] classBuckets = freeLists[classIndex];

        MemorySegment cached = classBuckets[localShard].poll();
        if (cached != null) {
            return new Allocation(cached, localShard);
        }

        int rightNeighbor = (localShard + 1) & shardMask;
        cached = classBuckets[rightNeighbor].poll();
        if (cached != null) {
            return new Allocation(cached, rightNeighbor);
        }

        int leftNeighbor = (localShard - 1) & shardMask;
        cached = classBuckets[leftNeighbor].poll();
        if (cached != null) {
            return new Allocation(cached, leftNeighbor);
        }

        Arena arena = getOrCreateShardArena(localShard);
        return new Allocation(arena.allocate(SIZE_CLASSES[classIndex], alignment), localShard);
    }

    /* default */ void returnSegment(long originalCapacity, int originShard, MemorySegment segment) {
        if (closed.get()) {
            return;
        }

        int classIndex = getSizeClassIndex(originalCapacity);
        if (classIndex < 0) {
            long totalOverflowReturns = overflowReturnCount.incrementAndGet();
            long totalOverflowReturnedBytes = overflowReturnedBytes.addAndGet(originalCapacity);
            CommunityOverflowReturnEvent.emit(
                    originalCapacity,
                    totalOverflowReturns,
                    totalOverflowReturnedBytes
            );
            return;
        }

        freeLists[classIndex][originShard].offerIfBelow(SIZE_CLASS_CAPACITIES[classIndex], segment);
    }

    /* default */ void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }

        for (Bucket[] classBuckets : freeLists) {
            for (Bucket bucket : classBuckets) {
                bucket.clear();
            }
        }

        for (int shardIndex = 0; shardIndex < SHARD_COUNT; shardIndex++) {
            @SuppressWarnings("PMD.CloseResource")
            Arena arena = shardArenas.getAndSet(shardIndex, null);
            if (arena != null) {
                arena.close();
            }
        }
    }

    private Arena getOrCreateShardArena(int shard) {
        if (closed.get()) {
            throw new IllegalStateException(POOL_CLOSED_MESSAGE);
        }

        Arena arena = shardArenas.get(shard);
        if (arena != null) {
            return arena;
        }

        Object initLock = shardInitLocks[shard];
        synchronized (initLock) {
            if (closed.get()) {
                throw new IllegalStateException(POOL_CLOSED_MESSAGE);
            }

            arena = shardArenas.get(shard);
            if (arena == null) {
                //CHECKSTYLE:OFF
                // Direct shared-arena allocation: this pool is the sole owner of one arena
                // per shard. Shared scope is required so LoanedBuffer segments can cross
                // thread boundaries without caller-managed arena lifetimes. Lifecycle is
                // deterministic: pool.close() closes shard arenas in order.
                arena = Arena.ofShared();
                //CHECKSTYLE:ON

                if (closed.get()) {
                    arena.close();
                    throw new IllegalStateException(POOL_CLOSED_MESSAGE);
                }

                shardArenas.set(shard, arena);
            }
        }

        return arena;
    }

    private Allocation allocateFromShardOverflow(int localShard, long capacityBytes, long alignment) {
        return new Allocation(getOrCreateShardArena(localShard).allocate(capacityBytes, alignment), localShard);
    }

    private static int getSizeClassIndex(long requestedBytes) {
        for (int i = 0; i < SIZE_CLASSES.length; i++) {
            if (requestedBytes <= SIZE_CLASSES[i]) {
                return i;
            }
        }
        return -1;
    }

    private static int clampPowerOfTwo(int value, int min, int max) {
        int clamped = Math.clamp(value, min, max);
        int lower = Integer.highestOneBit(clamped);
        if (lower == clamped) {
            return clamped;
        }
        int upper = lower << 1;
        if (upper > max || upper <= 0) {
            return lower;
        }
        return upper;
    }

    /* default */ record Allocation(MemorySegment segment, int originShard) {
    }

    private static final class Bucket {

        private final Queue<MemorySegment> queue = new ConcurrentLinkedQueue<>();
        private final AtomicInteger depth = new AtomicInteger(0);

        /* default */ MemorySegment poll() {
            MemorySegment segment = queue.poll();
            if (segment != null) {
                depth.decrementAndGet();
            }
            return segment;
        }

        /* default */ boolean offerIfBelow(int capacity, MemorySegment segment) {
            while (true) {
                int current = depth.get();
                if (current >= capacity) {
                    return false;
                }
                if (depth.compareAndSet(current, current + 1)) {
                    if (queue.offer(segment)) {
                        return true;
                    }
                    depth.decrementAndGet();
                    return false;
                }
            }
        }

        /* default */ void clear() {
            queue.clear();
            depth.set(0);
        }
    }
}
