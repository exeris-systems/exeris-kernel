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
 * @since 0.5.0
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

    @Override
    public LoanedBuffer allocate(AllocationHint hint) {
        return hint == AllocationHint.JUMBO
                ? allocateInfrastructure(hint.sizeBytes())
                : allocateNetwork(hint.sizeBytes());
    }

    @Override
    public LoanedBuffer allocateNetwork(int estimatedBytes) {
        checkOpen();
        if (estimatedBytes <= 0) {
            throw new IllegalArgumentException("estimatedBytes must be > 0, got: " + estimatedBytes);
        }
        return allocateBuffer(estimatedBytes);
    }

    @Override
    public LoanedBuffer allocateCarrierSlab(int carrierIndex) {
        checkOpen();
        return allocateBuffer(AllocationHint.STREAMING_CHUNK.sizeBytes());
    }

    @Override
    public LoanedBuffer allocateInfrastructure(long sizeBytes) {
        checkOpen();
        if (sizeBytes <= 0) {
            throw new IllegalArgumentException("sizeBytes must be > 0, got: " + sizeBytes);
        }
        return allocateBuffer(sizeBytes);
    }

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
