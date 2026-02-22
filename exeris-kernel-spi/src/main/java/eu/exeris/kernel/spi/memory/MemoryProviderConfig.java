/*
 * Copyright (C) 2025-2026 Exeris. All rights reserved.
 *
 * This code is part of the Exeris Systems.
 * Distributed under the proprietary Exeris Software License.
 * Unauthorized copying or distribution is prohibited.
 */
package eu.exeris.kernel.spi.memory;

import java.util.Objects;

/**
 * SPI: Immutable configuration record consumed by {@link MemoryProvider#createAllocator}.
 *
 * <h2>Valhalla Readiness</h2>
 * <p>Declared as a {@code value record} — the JVM eliminates the object header and
 * may flatten instances in arrays (JEP 401). No identity required; all fields are
 * primitives or value-safe types.
 *
 * <h2>Tier Separation (The Wall)</h2>
 * <p>This record exposes <em>logical</em> configuration knobs only. Enterprise
 * implementations map these to io_uring registration flags, huge-page hints, and
 * NUMA affinity parameters internally — none of that leaks here.
 *
 * @param totalOffHeapBytes      Total off-heap budget in bytes allocated to this provider.
 *                               {@code -1} disables the GlobalArbiter path (legacy/community mode).
 * @param networkOffHeapThreshold Byte threshold above which network allocations go off-heap.
 *                                Default {@code 32 * 1024} (32 KB).
 * @param carrierCount            Number of carrier threads (= number of affine slab pools).
 *                                Must be {@code >= 1}.
 * @param leakDetection           Leak detection mode. {@code DISABLED} in production.
 * @param jfrEnabled              Whether to emit JFR events on allocation lifecycle.
 *
 * @since 0.5.0
 * @see MemoryProvider
 */
public record MemoryProviderConfig(
        long   totalOffHeapBytes,
        int    networkOffHeapThreshold,
        int    carrierCount,
        LeakDetectionMode leakDetection,
        boolean jfrEnabled
) {

    /** Minimum valid carrier count — at least one carrier loop must be present. */
    private static final int MIN_CARRIER_COUNT = 1;

    /** Strict validation at construction time — fail fast, not silently. */
    public MemoryProviderConfig {
        if (carrierCount < MIN_CARRIER_COUNT) {
            throw new IllegalArgumentException("carrierCount must be >= 1, got: " + carrierCount);
        }
        if (networkOffHeapThreshold < 0) {
            throw new IllegalArgumentException("networkOffHeapThreshold must be >= 0");
        }
        Objects.requireNonNull(leakDetection, "leakDetection must not be null");
    }

    /**
     * Default configuration for development / unit tests.
     * Off-heap disabled (heap only), single carrier, no leak detection.
     */
    public static MemoryProviderConfig defaults() {
        return new MemoryProviderConfig(
                -1L,
                32 * 1_024,
                1,
                LeakDetectionMode.DISABLED,
                false
        );
    }

    /**
     * Configuration suitable for a production JVM with {@code totalOffHeapMb} megabytes
     * of off-heap budget, {@code carriers} carrier threads, and JFR enabled.
     *
     * @param totalOffHeapMb off-heap budget in megabytes
     * @param carriers       number of carrier threads
     * @return production-ready configuration
     */
    public static MemoryProviderConfig production(int totalOffHeapMb, int carriers) {
        return new MemoryProviderConfig(
                (long) totalOffHeapMb * 1_024 * 1_024,
                32 * 1_024,
                carriers,
                LeakDetectionMode.DISABLED,
                true
        );
    }
}


