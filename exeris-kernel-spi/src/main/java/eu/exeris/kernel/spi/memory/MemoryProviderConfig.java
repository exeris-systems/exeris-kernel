/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
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
 * @since 0.5
 * @see MemoryProvider
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
     * Rejects an unusable configuration at construction time rather than letting the
     * allocator fail later: the carrier count must be at least one, the network threshold
     * non-negative, the off-heap budget either positive or the {@code -1} sentinel, and the
     * leak-detection mode present.
     *
     * @throws IllegalArgumentException if any of those invariants is violated — in
     *                                  particular for {@code totalOffHeapBytes == 0}, which
     *                                  is not "no budget" but a budget of nothing
     * @throws NullPointerException     if {@code leakDetection} is {@code null}
     */
    public MemoryProviderConfig {
        if (carrierCount < MIN_CARRIER_COUNT) {
            throw new IllegalArgumentException("carrierCount must be >= 1, got: " + carrierCount);
        }
        if (networkOffHeapThreshold < 0) {
            throw new IllegalArgumentException("networkOffHeapThreshold must be >= 0");
        }
        if (totalOffHeapBytes < -1) {
            throw new IllegalArgumentException(
                    "totalOffHeapBytes must be >= -1 (-1 = disabled), got: " + totalOffHeapBytes);
        }
        if (totalOffHeapBytes == 0) {
            throw new IllegalArgumentException(
                    "totalOffHeapBytes == 0 is not a valid budget; use -1 to disable off-heap");
        }
        Objects.requireNonNull(leakDetection, "leakDetection must not be null");
    }

    /**
     * Returns a copy of this configuration with the specified {@link LeakDetectionMode}.
     *
     * <p>This record is immutable; the receiver is left unchanged.
     *
     * {@snippet lang="java" :
     * MemoryProviderConfig paranoid = MemoryProviderConfig.defaults()
     *         .withLeakDetection(LeakDetectionMode.PARANOID);
     * }
     *
     * @param mode the new leak detection mode; must not be {@code null}
     * @return a new {@link MemoryProviderConfig} with all fields identical except
     *         {@code leakDetection}
     * @throws NullPointerException if {@code mode} is {@code null}
     * @apiNote Written for TCK and integration tests that need to raise the detection mode
     *          without restating the rest of the configuration.
     */
    public MemoryProviderConfig withLeakDetection(LeakDetectionMode mode) {
        Objects.requireNonNull(mode, "mode must not be null");
        return new MemoryProviderConfig(
                totalOffHeapBytes,
                networkOffHeapThreshold,
                carrierCount,
                mode,
                jfrEnabled
        );
    }

    /**
     * Returns the development and unit-test configuration: off-heap disabled
     * ({@code totalOffHeapBytes == -1}, heap only), one carrier, a 32 KB network threshold,
     * leak detection off and no JFR events.
     *
     * @return a configuration that needs no off-heap budget and adds no tracking overhead
     * @apiNote Not a production baseline — it leaves the runtime without an off-heap budget
     *          and without JFR allocation events. Use {@link #production(int, int)} for that.
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
     * <p>The network off-heap threshold is 32 KB and leak detection is
     * {@link LeakDetectionMode#DISABLED} — the only mode that adds no per-buffer tracking
     * cost on a production hot path.
     *
     * @param totalOffHeapMb off-heap budget in megabytes; must be &gt; 0 and &le; {@value #MAX_OFF_HEAP_MB}
     * @param carriers       number of carrier threads; must be &ge; 1
     * @return production-ready configuration
     * @throws IllegalArgumentException if {@code totalOffHeapMb} is &le; 0 or exceeds
     *                                  {@value #MAX_OFF_HEAP_MB} MB, or if {@code carriers}
     *                                  is &lt; 1
     */
    public static MemoryProviderConfig production(int totalOffHeapMb, int carriers) {
        if (totalOffHeapMb <= 0) {
            throw new IllegalArgumentException("totalOffHeapMb must be > 0, got: " + totalOffHeapMb);
        }
        if (totalOffHeapMb > MAX_OFF_HEAP_MB) {
            throw new IllegalArgumentException(
                    "totalOffHeapMb exceeds maximum allowed (" + MAX_OFF_HEAP_MB + " MB), got: " + totalOffHeapMb);
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
