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
 * <p>When {@code jfrEnabled} is {@code true} in {@link MemoryProviderConfig}, allocation
 * ({@link CommunityAllocationEvent}) and release ({@link CommunityReleaseEvent}) events are
 * emitted to the JFR subsystem for zero-overhead profiling.
 *
 * @since 0.5.0
 * @see CommunityMemoryProvider
 */
final class CommunityMemoryAllocator implements MemoryAllocator {

    private static final long CACHE_LINE_ALIGNMENT = 64L;
    private static final int DEFAULT_NETWORK_OFF_HEAP_THRESHOLD = 32 * 1_024;

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
        validateSupportedConfig(config);
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
    @SuppressWarnings({"java:S1181", "java:S1141"})
    public LoanedBuffer allocateInfrastructure(long sizeBytes) {
        checkOpen();
        if (sizeBytes <= 0) {
            throw new IllegalArgumentException("sizeBytes must be > 0, got: " + sizeBytes);
        }
        try {
            // Infrastructure allocations always use shared arenas (they may cross thread boundaries).
            // CHECKSTYLE:OFF — Arena.ofShared() is legal here: this IS the allocator implementation.
            Arena sharedArena = Arena.ofShared(); //NOPMD CloseResource — ownership in CommunityLoanedBuffer.onRelease
            // CHECKSTYLE:ON
            AbstractLoanedBuffer buf;
            try {
                buf = CommunityLoanedBuffer.allocateOwned(sizeBytes, CACHE_LINE_ALIGNMENT, sharedArena);
            } catch (Throwable t) {
                try {
                    sharedArena.close();
                } catch (Throwable closeEx) {
                    t.addSuppressed(closeEx);
                }
                throw t;
            }
            trackAllocation(sizeBytes);
            buf.addCloseAction(new ReleaseAction(
                    sizeBytes, releaseCount, allocatedBytes, jfrEnabled));
            if (leakDetection != LeakDetectionMode.DISABLED) {
                buf.enableLeakTracking(leakTracker);
            }
            return buf;
        } catch (OutOfMemoryError oom) {
            throw new MemoryExhaustedException(sizeBytes, allocatedBytes.get(), oom);
        }
    }

    // =========================================================================
    // MemoryAllocator — diagnostic / lifecycle
    // =========================================================================

    @Override
    public MemoryStats stats() {
        long allocated = allocatedBytes.get();
        return new MemoryStats(
                -1L,         // Community: unbounded off-heap allocator (no fixed total budget; sentinel value)
                allocated,
                0L,          // Community: free bytes not applicable when totalBytes == -1 (unbounded; not tracked)
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
            buf.addCloseAction(new ReleaseAction(
                    capacityBytes, releaseCount, allocatedBytes, jfrEnabled));
            if (leakDetection != LeakDetectionMode.DISABLED) {
                buf.enableLeakTracking(leakTracker);
            }
            return buf;
        } catch (OutOfMemoryError oom) {
            throw new MemoryExhaustedException(capacityBytes, allocatedBytes.get(), oom);
        }
    }
    @SuppressWarnings("java:S1181")
    private AbstractLoanedBuffer allocateShared(long capacityBytes) {
        // CHECKSTYLE:OFF — Arena.ofShared() is legal here: this IS the allocator implementation.
        Arena shared = Arena.ofShared();
        // CHECKSTYLE:ON
        try {
            return CommunityLoanedBuffer.allocateOwned(capacityBytes, CACHE_LINE_ALIGNMENT, shared);
        } catch (Throwable t) {
            try {
                shared.close();
            } catch (Throwable closeEx) {
                t.addSuppressed(closeEx);
            }
            throw t;
        }
    }

    private void trackAllocation(long bytes) {
        long count = allocationCount.incrementAndGet();
        long current = allocatedBytes.addAndGet(bytes);
        peakAllocated.getAndAccumulate(current, Math::max);
        if (jfrEnabled) {
            CommunityAllocationEvent.emit(bytes, count);
        }
    }

    private void checkOpen() {
        if (closed.get()) {
            throw new IllegalStateException("CommunityMemoryAllocator has been closed");
        }
    }

    private static void validateSupportedConfig(MemoryProviderConfig config) {
        if (config.totalOffHeapBytes() != -1L) {
            throw new IllegalArgumentException(
                    "CommunityMemoryAllocator does not support fixed off-heap budgets; "
                            + "use totalOffHeapBytes=-1, got: " + config.totalOffHeapBytes());
        }
        if (config.networkOffHeapThreshold() != DEFAULT_NETWORK_OFF_HEAP_THRESHOLD) {
            throw new IllegalArgumentException(
                    "CommunityMemoryAllocator does not support custom networkOffHeapThreshold; "
                            + "use " + DEFAULT_NETWORK_OFF_HEAP_THRESHOLD
                            + ", got: " + config.networkOffHeapThreshold());
        }
    }

    /**
     * Zero-GC close action that decrements telemetry counters on buffer release.
     *
     * <p>Declared as a {@code static} nested class to eliminate the implicit reference to the
     * enclosing {@link CommunityMemoryAllocator} that a non-static inner class would carry.
     * The required allocator state is injected explicitly as constructor arguments so each
     * instance holds exactly three references and one {@code long} — no hidden overhead.
     */
    private static final class ReleaseAction implements Runnable {

        private final long bytes;
        private final AtomicLong releaseCount;
        private final AtomicLong allocatedBytes;
        private final boolean jfrEnabled;

        /* default */ ReleaseAction(long bytes,
                                    AtomicLong releaseCount,
                                    AtomicLong allocatedBytes,
                                    boolean jfrEnabled) {
            this.bytes          = bytes;
            this.releaseCount   = releaseCount;
            this.allocatedBytes = allocatedBytes;
            this.jfrEnabled     = jfrEnabled;
        }

        @Override
        public void run() {
            long count = releaseCount.incrementAndGet();
            allocatedBytes.addAndGet(-bytes);
            if (jfrEnabled) {
                CommunityReleaseEvent.emit(bytes, count);
            }
        }
    }
}
