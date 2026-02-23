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
 * <p>This is a {@code record} structured so it can be migrated to a {@code value record}
 * (JEP 401) once Valhalla is available in the target toolchain. No identity is required;
 * all fields are primitives or value-safe types.
 *
 * <h2>Tier Separation (The Wall)</h2>
 * <p>This record exposes <em>logical</em> configuration knobs only. Enterprise
 * implementations map these to io_uring registration flags, huge-page hints, and
 * NUMA affinity parameters internally — none of that leaks here.
 *
 * @param totalOffHeapBytes       Total off-heap budget in bytes allocated to this provider.
 *                                {@code -1} disables the GlobalArbiter path (legacy/community mode).
 * @param networkOffHeapThreshold Byte threshold above which network allocations go off-heap.
 *                                Default {@code 32 * 1024} (32 KB).
 * @param carrierCount            Number of carrier threads (= number of affine slab pools).
 *                                Must be {@code >= 1}.
 * @param leakDetection           Leak detection mode. {@code DISABLED} in production.
 * @param jfrEnabled              Whether to emit JFR events on allocation lifecycle.
 * @see MemoryProvider
 * @since 0.5.0
 */
public record MemoryProviderConfig(
        long totalOffHeapBytes,
        int networkOffHeapThreshold,
        int carrierCount,
        LeakDetectionMode leakDetection,
        boolean jfrEnabled
) {

    /**
     * Minimum valid carrier count — at least one carrier loop must be present.
     */
    private static final int MIN_CARRIER_COUNT = 1;
    /**
     * Maximum safe off-heap budget in MB before integer overflow at * 1024 * 1024.
     */
    private static final int MAX_OFF_HEAP_MB = Integer.MAX_VALUE / (1_024 * 1_024);

    /**
     * Strict validation at construction time — fail fast, not silently.
     */
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
     * @param totalOffHeapMb off-heap budget in megabytes; must be &gt; 0 and &le; {@value #MAX_OFF_HEAP_MB}
     * @param carriers       number of carrier threads
     * @return production-ready configuration
     * @throws IllegalArgumentException if {@code totalOffHeapMb} is negative or would overflow {@code long}
     */
    public static MemoryProviderConfig production(int totalOffHeapMb, int carriers) {
        if (totalOffHeapMb <= 0) {
            throw new IllegalArgumentException("totalOffHeapMb must be > 0, got: " + totalOffHeapMb);
        }
        if (totalOffHeapMb > MAX_OFF_HEAP_MB) {
            throw new IllegalArgumentException("totalOffHeapMb would overflow long: " + totalOffHeapMb);
        }
        return new MemoryProviderConfig(
                (long) totalOffHeapMb * 1_024 * 1_024,
                32 * 1_024,
                carriers,
                LeakDetectionMode.DISABLED,
                true
        );
    }
}


