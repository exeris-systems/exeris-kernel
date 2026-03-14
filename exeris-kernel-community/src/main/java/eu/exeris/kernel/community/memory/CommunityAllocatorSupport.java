/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.community.memory;

import eu.exeris.kernel.spi.memory.MemoryProviderConfig;

import java.util.concurrent.atomic.AtomicLong;

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
                                              long bytes) {
        long count = allocationCount.incrementAndGet();
        long current = allocatedBytes.addAndGet(bytes);
        peakAllocated.getAndAccumulate(current, Math::max);
        if (jfrEnabled) {
            CommunityAllocationEvent.emit(bytes, count);
        }
    }
}
