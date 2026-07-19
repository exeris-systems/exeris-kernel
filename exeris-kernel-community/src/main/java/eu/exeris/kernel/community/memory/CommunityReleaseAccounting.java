/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.community.memory;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Per-allocator release-accounting helper: holds the allocator's shared release counters and JFR
 * sampling config, and performs the per-buffer release bookkeeping in {@link #release(long)} (the
 * decrement mirror of {@link CommunityAllocatorSupport#trackAllocation}).
 *
 * <p>One instance is created per {@link CommunityMemoryAllocator}; each buffer holds a reference to it
 * and calls {@link #release(long)} from its {@code onRelease()}. This replaces the former per-buffer
 * {@code Runnable} close-action, so no bookkeeping object is allocated on the allocation hot path — the
 * buffer already knows its own capacity, and the counters/JFR config are allocator-wide constants.
 */
final class CommunityReleaseAccounting {

    private final AtomicLong releaseCount;
    private final AtomicLong allocatedBytes;
    private final boolean jfrEnabled;
    private final CommunityMemoryJfrSampling jfrSampling;

    /* default */ CommunityReleaseAccounting(AtomicLong releaseCount,
                                             AtomicLong allocatedBytes,
                                             boolean jfrEnabled,
                                             CommunityMemoryJfrSampling jfrSampling) {
        this.releaseCount = releaseCount;
        this.allocatedBytes = allocatedBytes;
        this.jfrEnabled = jfrEnabled;
        this.jfrSampling = jfrSampling;
    }

    /**
     * Records the release of a buffer of {@code bytes} capacity — identical bookkeeping to the former
     * per-buffer close-action: bump the release count, return the bytes to the outstanding total, and
     * emit a sampled JFR event when enabled.
     *
     * @param bytes the released buffer's capacity (the same value {@code trackAllocation} added)
     */
    /* default */ void release(long bytes) {
        long count = releaseCount.incrementAndGet();
        allocatedBytes.addAndGet(-bytes);
        if (jfrEnabled && jfrSampling.shouldEmit(count)) {
            CommunityReleaseEvent.emit(bytes, count);
        }
    }
}
