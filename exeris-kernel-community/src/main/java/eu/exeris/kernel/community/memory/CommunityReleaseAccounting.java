/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.memory;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Per-allocator release-accounting helper: holds the allocator's shared release counters and JFR
 * sampling config, and performs the per-buffer release bookkeeping in {@link #release(long)} (the
 * decrement mirror of {@link CommunityAllocatorSupport#trackAllocation}).
 *
 * <p>One instance is created per {@link CommunityMemoryAllocator}; each buffer holds a reference to
 * it and calls {@link #release(long)} from its {@code onRelease()}. No per-buffer bookkeeping object
 * is allocated on the release path: the buffer already knows its own capacity, and the counters and
 * JFR sampling config are shared, allocator-wide state held by this one instance.
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
     * Records the release of a buffer of {@code bytes} capacity: bumps the release count, returns
     * the bytes to the outstanding total, and emits a sampled JFR event when enabled.
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
