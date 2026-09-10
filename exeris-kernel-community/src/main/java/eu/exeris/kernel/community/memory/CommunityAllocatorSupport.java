/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.memory;

import eu.exeris.kernel.spi.memory.MemoryProviderConfig;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Community: static allocation-bookkeeping helpers used by {@link CommunityMemoryAllocator},
 * factored out so the allocator's own methods stay focused on the {@link
 * eu.exeris.kernel.spi.memory.MemoryAllocator} contract they implement.
 *
 * <p>Holds no state of its own — every method takes the caller's configuration or counters
 * as parameters and mutates only what is passed in.
 */
final class CommunityAllocatorSupport {

    private CommunityAllocatorSupport() {
    }

    /* default */ static void validateSupportedConfig(MemoryProviderConfig config, int defaultNetworkThreshold) {
        if (config.totalOffHeapBytes() != -1L) {
            throw new IllegalArgumentException(
                    "CommunityMemoryAllocator does not support fixed off-heap budgets; "
                            + "use totalOffHeapBytes=-1, got: " + config.totalOffHeapBytes()
            );
        }
        if (config.networkOffHeapThreshold() != defaultNetworkThreshold) {
            throw new IllegalArgumentException(
                    "CommunityMemoryAllocator does not support custom networkOffHeapThreshold; "
                            + "use " + defaultNetworkThreshold
                            + ", got: " + config.networkOffHeapThreshold()
            );
        }
    }

    /* default */ static void trackAllocation(AtomicLong allocationCount,
                                              AtomicLong allocatedBytes,
                                              AtomicLong peakAllocated,
                                              boolean jfrEnabled,
                                              CommunityMemoryJfrSampling jfrSampling,
                                              long bytes) {
        long count = allocationCount.incrementAndGet();
        long current = allocatedBytes.addAndGet(bytes);
        peakAllocated.getAndAccumulate(current, Math::max);
        if (jfrEnabled && jfrSampling.shouldEmit(count)) {
            CommunityAllocationEvent.emit(bytes, count);
        }
    }
}
