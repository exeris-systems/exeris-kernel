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
import eu.exeris.kernel.core.memory.LeakTracker;
import eu.exeris.kernel.spi.exceptions.memory.MemoryExhaustedException;
import eu.exeris.kernel.spi.memory.AllocationHint;
import eu.exeris.kernel.spi.memory.LeakDetectionMode;
import eu.exeris.kernel.spi.memory.LoanedBuffer;
import eu.exeris.kernel.spi.memory.MemoryAllocator;
import eu.exeris.kernel.spi.memory.MemoryProviderConfig;
import eu.exeris.kernel.spi.memory.MemoryStats;

import java.lang.foreign.Arena;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Community: {@link MemoryAllocator} implementation using per-buffer
 * {@code Arena.ofShared()} allocations (syscall-based, no global pool).
 *
 * <h2>Community Tier Contract</h2>
 * <ul>
 *   <li>Each {@link LoanedBuffer} owns its own {@link Arena} — lifecycle is buffer-scoped.</li>
 *   <li>No {@code GlobalMemoryArbiter} — allocations go directly to the OS via mmap.</li>
 *   <li>No global byte limits; {@link MemoryStats} reflects live allocation counts only.</li>
 *   <li>All buffers use {@code Arena.ofShared()} to satisfy the SPI cross-thread ownership
 *       contract: any thread may call {@code close()} on a {@link LoanedBuffer} regardless
 *       of which thread originally allocated it.</li>
 * </ul>
 *
 * <h2>Thread Safety</h2>
 * <p>This allocator is safe for concurrent invocation from multiple Virtual Threads.
 * Each call produces an independent buffer with its own arena; no shared mutable state
 * is accessed except the {@link java.util.concurrent.atomic.AtomicLong} telemetry counters.
 *
 * <h2>JFR Events</h2>
 * <p>When {@code jfrEnabled} is {@code true} in {@link MemoryProviderConfig}, allocation and
 * release events are emitted to the JFR subsystem for zero-overhead profiling.
 *
 * @since 0.5.0
 * @see CommunityMemoryProvider
 */
final class CommunityMemoryAllocator implements MemoryAllocator {

    private static final long CACHE_LINE_ALIGNMENT = 64L;

    private final boolean jfrEnabled;
    private final LeakDetectionMode leakDetection;
    private final LeakTracker leakTracker;
    private final AtomicBoolean closed = new AtomicBoolean(false);

    // ---- Telemetry counters (Community: no off-heap budget tracking) ----
    private final AtomicLong allocationCount   = new AtomicLong(0);
    private final AtomicLong releaseCount      = new AtomicLong(0);
    private final AtomicLong allocatedBytes    = new AtomicLong(0);
    private final AtomicLong peakAllocated     = new AtomicLong(0);

    /* default */ CommunityMemoryAllocator(MemoryProviderConfig config) {
        this.jfrEnabled    = config.jfrEnabled();
        this.leakDetection = config.leakDetection();
        this.leakTracker   = new LeakTracker(config.leakDetection());
    }

    // =========================================================================
    // MemoryAllocator — hot-path methods
    // =========================================================================

    @Override
    public LoanedBuffer allocate(AllocationHint hint) {
        if (hint == AllocationHint.JUMBO) {
            return allocateInfrastructure(hint.sizeBytes());
        }
        return allocateNetwork(hint.sizeBytes());
    }

    @Override
    public LoanedBuffer allocateNetwork(int estimatedBytes) {
        checkOpen();
        if (estimatedBytes <= 0) {
            throw new IllegalArgumentException("estimatedBytes must be > 0, got: " + estimatedBytes);
        }
        return doAllocate(estimatedBytes);
    }

    @Override
    public LoanedBuffer allocateCarrierSlab(int carrierIndex) {
        checkOpen();
        // Community: carrier affinity is a no-op — all slabs are equivalent.
        // Use STREAMING_CHUNK size as a reasonable slab default.
        return doAllocate(AllocationHint.STREAMING_CHUNK.sizeBytes());
    }

    @Override
    public LoanedBuffer allocateInfrastructure(long sizeBytes) {
        checkOpen();
        if (sizeBytes <= 0) {
            throw new IllegalArgumentException("sizeBytes must be > 0, got: " + sizeBytes);
        }
        // Infrastructure allocations always use shared arenas (they may cross thread boundaries).
        // CHECKSTYLE:OFF — Arena.ofShared() is legal here: this IS the allocator implementation.
        Arena sharedArena = Arena.ofShared(); //NOPMD CloseResource — ownership in CommunityLoanedBuffer.onRelease
        // CHECKSTYLE:ON
        AbstractLoanedBuffer buf = CommunityLoanedBuffer.allocateOwned(
                sizeBytes, CACHE_LINE_ALIGNMENT, sharedArena);
        trackAllocation(sizeBytes);
        buf.addCloseAction(new ReleaseAction(sizeBytes));
        if (leakDetection != LeakDetectionMode.DISABLED) {
            buf.enableLeakTracking(leakTracker);
        }
        return buf;
    }

    // =========================================================================
    // MemoryAllocator — diagnostic / lifecycle
    // =========================================================================

    @Override
    public MemoryStats stats() {
        long allocated = allocatedBytes.get();
        return new MemoryStats(
                -1L,         // Community: no fixed total budget (sentinel: unknown/heap-only)
                allocated,
                0L,          // Community: no fixed free budget (not tracked; totalBytes == -1)
                allocationCount.get(),
                releaseCount.get(),
                peakAllocated.get(),
                0,           // Community: no carrier pools
                leakTracker.leakCount(),
                leakDetection
        );
    }

    @Override
    public void close() {
        closed.set(true);
    }

    // =========================================================================
    // Internal helpers
    // =========================================================================

    private LoanedBuffer doAllocate(long capacityBytes) {
        try {
            AbstractLoanedBuffer buf = allocateShared(capacityBytes);
            trackAllocation(capacityBytes);
            buf.addCloseAction(new ReleaseAction(capacityBytes));
            if (leakDetection != LeakDetectionMode.DISABLED) {
                buf.enableLeakTracking(leakTracker);
            }
            return buf;
        } catch (OutOfMemoryError oom) {
            throw new MemoryExhaustedException(capacityBytes, allocatedBytes.get(), oom);
        }
    }

    private AbstractLoanedBuffer allocateShared(long capacityBytes) {
        // CHECKSTYLE:OFF — Arena.ofShared() is legal here: this IS the allocator implementation.
        Arena shared = Arena.ofShared();
        // CHECKSTYLE:ON
        return CommunityLoanedBuffer.allocateOwned(capacityBytes, CACHE_LINE_ALIGNMENT, shared);
    }

    private void trackAllocation(long bytes) {
        allocationCount.incrementAndGet();
        long current = allocatedBytes.addAndGet(bytes);
        peakAllocated.getAndAccumulate(current, Math::max);
        if (jfrEnabled) {
            CommunityAllocationEvent.emit(bytes, allocationCount.get());
        }
    }


    private void checkOpen() {
        if (closed.get()) {
            throw new IllegalStateException("CommunityMemoryAllocator has been closed");
        }
    }

    /**
     * Zero-GC close action that decrements telemetry counters on buffer release.
     *
     * <p>Replaces the capturing lambda {@code () -> trackRelease(capacityBytes)}.
     * A capturing lambda creates a new object on every {@code allocate()} call
     * (confirmed by the Zero-GC JFR Monitor TCK). This inner class stores the
     * {@code long} directly as a field — same object footprint, no anonymous class
     * creation overhead beyond the single field.
     */
    private final class ReleaseAction implements Runnable {

        private final long bytes;

        /* default */ ReleaseAction(long bytes) {
            this.bytes = bytes;
        }

        @Override
        public void run() {
            releaseCount.incrementAndGet();
            allocatedBytes.addAndGet(-bytes);
        }
    }
}
