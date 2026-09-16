/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.memory;

import eu.exeris.kernel.core.memory.AbstractLoanedBuffer;
import eu.exeris.kernel.core.memory.LeakTracker;
import eu.exeris.kernel.spi.exceptions.memory.MemoryExhaustedException;
import eu.exeris.kernel.spi.memory.AllocationHint;
import eu.exeris.kernel.spi.memory.LeakDetectionMode;
import eu.exeris.kernel.spi.memory.LoanedBuffer;
import eu.exeris.kernel.spi.memory.MemoryAllocator;
import eu.exeris.kernel.spi.memory.MemoryProviderConfig;
import eu.exeris.kernel.spi.memory.MemoryStats;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Community {@link MemoryAllocator}: shard-based arena pool with size classes
 * to reduce per-allocation contention. Pool manages shard-local shared arenas
 * with lock-free reuse queues.
 *
 * <p><b>Allocation:</b> delegates every {@code allocate*} call to its
 * {@link CommunityArenaShardPool} via {@link CommunityArenaBuffers#allocateOwned}; this
 * class performs no native allocation of its own.
 * <p><b>Thread confinement:</b> any thread — every counter is an {@link AtomicLong} or
 * {@link AtomicBoolean}, and allocation itself is delegated to the shard pool's own
 * thread-safe shard selection.
 * <p><b>Ownership:</b> owns exactly one {@link CommunityArenaShardPool}, created in the
 * constructor; {@link #close()} closes it exactly once, guarded by a CAS on
 * {@code closed}. Individual buffers manage their own release through
 * {@link CommunityReleaseAccounting} — this class does not track them.
 *
 * @since 0.5
 */
final class CommunityMemoryAllocator implements MemoryAllocator {

    private static final long CACHE_LINE_ALIGNMENT = 64L;
    private static final int DEFAULT_NETWORK_OFF_HEAP_THRESHOLD = 32 * 1_024;

    private final boolean jfrEnabled;
    private final CommunityMemoryJfrSampling jfrSampling;
    private final LeakDetectionMode leakDetection;
    private final LeakTracker leakTracker;
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final CommunityArenaShardPool arenaPool;

    private final AtomicLong allocationCount = new AtomicLong(0);
    private final AtomicLong releaseCount = new AtomicLong(0);
    private final AtomicLong allocatedBytes = new AtomicLong(0);
    private final AtomicLong peakAllocated = new AtomicLong(0);
    private final CommunityReleaseAccounting releaseAccounting;

    /* default */ CommunityMemoryAllocator(MemoryProviderConfig config) {
        CommunityAllocatorSupport.validateSupportedConfig(config, DEFAULT_NETWORK_OFF_HEAP_THRESHOLD);
        this.jfrEnabled = config.jfrEnabled();
        this.jfrSampling = CommunityMemoryJfrSampling.fromSystemProperties();
        this.leakDetection = config.leakDetection();
        this.leakTracker = new LeakTracker(config.leakDetection());
        this.arenaPool = new CommunityArenaShardPool();
        this.releaseAccounting =
                new CommunityReleaseAccounting(releaseCount, allocatedBytes, jfrEnabled, jfrSampling);
    }

    /**
     * Routes to {@link #allocateInfrastructure(long)} for {@link AllocationHint#JUMBO} and
     * to {@link #allocateNetwork(int)} for every other hint, using {@code hint.sizeBytes()}
     * as the requested size in both cases.
     *
     * @param hint semantic size hint
     * @return loaned buffer from the shard pool
     * @throws MemoryExhaustedException ({@code EX-MEM-1001}) if the shard pool's arena
     *                                   allocation fails with an {@link OutOfMemoryError}
     * @throws IllegalStateException    if this allocator has been closed
     */
    @Override
    public LoanedBuffer allocate(AllocationHint hint) {
        return hint == AllocationHint.JUMBO
                ? allocateInfrastructure(hint.sizeBytes())
                : allocateNetwork(hint.sizeBytes());
    }

    /**
     * Allocates a network buffer of {@code estimatedBytes} from the shard pool's
     * size-class buckets, reusing a pooled segment when the requested size fits within a
     * bucket's capacity.
     *
     * @param estimatedBytes estimated payload size in bytes; must be {@code > 0}
     * @return loaned buffer backed by a pool-owned segment
     * @throws IllegalArgumentException if {@code estimatedBytes <= 0}
     * @throws MemoryExhaustedException ({@code EX-MEM-1001}) if the shard pool's arena
     *                                   allocation fails with an {@link OutOfMemoryError}
     * @throws IllegalStateException    if this allocator has been closed
     */
    @Override
    public LoanedBuffer allocateNetwork(int estimatedBytes) {
        checkOpen();
        if (estimatedBytes <= 0) {
            throw new IllegalArgumentException("estimatedBytes must be > 0, got: " + estimatedBytes);
        }
        return allocateBuffer(estimatedBytes);
    }

    /**
     * Allocates a buffer sized for {@link AllocationHint#STREAMING_CHUNK} from the shard
     * pool.
     *
     * <p>The Community tier does not implement carrier affinity: {@code carrierIndex} is
     * accepted to satisfy the {@link MemoryAllocator} contract but otherwise ignored —
     * every call is routed through the same shard-selection logic as
     * {@link #allocateNetwork(int)}, keyed on the calling thread rather than the carrier.
     *
     * @param carrierIndex ignored by this implementation
     * @return loaned buffer sized for a streaming chunk
     * @throws MemoryExhaustedException ({@code EX-MEM-1001}) if the shard pool's arena
     *                                   allocation fails with an {@link OutOfMemoryError}
     * @throws IllegalStateException    if this allocator has been closed
     */
    @Override
    public LoanedBuffer allocateCarrierSlab(int carrierIndex) {
        checkOpen();
        return allocateBuffer(AllocationHint.STREAMING_CHUNK.sizeBytes());
    }

    /**
     * Allocates an infrastructure block of {@code sizeBytes} from the same shard pool used
     * by {@link #allocateNetwork(int)}; the Community tier does not distinguish
     * infrastructure allocations from network allocations.
     *
     * @param sizeBytes requested block size in bytes; must be {@code > 0}
     * @return loaned buffer wrapping the infrastructure segment
     * @throws IllegalArgumentException if {@code sizeBytes <= 0}
     * @throws MemoryExhaustedException ({@code EX-MEM-1001}) if the shard pool's arena
     *                                   allocation fails with an {@link OutOfMemoryError}
     * @throws IllegalStateException    if this allocator has been closed
     */
    @Override
    public LoanedBuffer allocateInfrastructure(long sizeBytes) {
        checkOpen();
        if (sizeBytes <= 0) {
            throw new IllegalArgumentException("sizeBytes must be > 0, got: " + sizeBytes);
        }
        return allocateBuffer(sizeBytes);
    }

    /**
     * Returns a point-in-time snapshot of this allocator's counters.
     *
     * <p>{@code totalBytes} is always {@code -1} (unknown/heap-only, per
     * {@link MemoryStats}) and {@code freeBytes} is always {@code 0}: the Community tier
     * requires an unbounded off-heap budget (see
     * {@link CommunityAllocatorSupport#validateSupportedConfig}) and does not track a
     * free-byte count.
     * {@code carrierPoolCount} is always {@code 0} because
     * {@link #allocateCarrierSlab(int)} does not maintain carrier-affine pools.
     *
     * @return current allocation metrics
     */
    @Override
    public MemoryStats stats() {
        long allocated = allocatedBytes.get();
        return new MemoryStats(
                -1L,
                allocated,
                0L,
                allocationCount.get(),
                releaseCount.get(),
                peakAllocated.get(),
                0,
                leakTracker.leakCount(),
                leakDetection
        );
    }

    /**
     * Closes the underlying {@link CommunityArenaShardPool}, closing every shard's native
     * arena and invalidating buffers still outstanding from it.
     *
     * <p>Idempotent: a second call observes {@code closed} already set and returns without
     * touching the pool again.
     */
    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            arenaPool.close();
        }
    }

    private LoanedBuffer allocateBuffer(long capacityBytes) {
        try {
            AbstractLoanedBuffer buffer = CommunityArenaBuffers.allocateOwned(
                    capacityBytes, CACHE_LINE_ALIGNMENT, arenaPool, releaseAccounting);
            CommunityAllocatorSupport.trackAllocation(
                    allocationCount,
                    allocatedBytes,
                    peakAllocated,
                    jfrEnabled,
                    jfrSampling,
                    capacityBytes
            );
            // Release accounting is now folded into the buffer's onRelease (see CommunityReleaseAccounting),
            // so no per-buffer close-action object is allocated on the hot path.
            if (leakDetection != LeakDetectionMode.DISABLED) {
                buffer.enableLeakTracking(leakTracker);
            }
            return buffer;
        } catch (OutOfMemoryError oom) {
            throw new MemoryExhaustedException(capacityBytes, 0L, oom);
        }
    }

    private void checkOpen() {
        if (closed.get()) {
            throw new IllegalStateException("CommunityMemoryAllocator has been closed");
        }
    }
}
