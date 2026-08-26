/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.spi.memory;

import java.util.Objects;

/**
 * Immutable snapshot of {@link MemoryAllocator} diagnostics.
 *
 * <h2>Valhalla Readiness</h2>
 * <p>This is a primitive-only {@code record} structured so it can be migrated to a
 * {@code value record} (JEP 401) once Valhalla is available in the target toolchain.
 * Arrays of {@code MemoryStats} are therefore expected to benefit from header elimination
 * and heap flattening on future JVMs, but no such behaviour is assumed or required today.
 *
 * <h2>Zero-Allocation Contract</h2>
 * <p>This record is returned by {@link MemoryAllocator#stats()} on the diagnostic path.
 * It is intentionally <em>not</em> on the hot allocation path, so the record allocation
 * per call is acceptable (diagnostics are low-frequency).
 *
 * @param totalBytes         Total off-heap budget configured for this allocator (bytes).
 * @param allocatedBytes     Bytes currently in use (live loaned buffers).
 * @param freeBytes          Bytes available for new allocations.
 * @param allocationCount    Cumulative count of all {@code allocate*} calls since start.
 * @param releaseCount       Cumulative count of buffers returned to the pool (refCount → 0).
 * @param peakAllocatedBytes Historical maximum of {@code allocatedBytes} observed.
 * @param carrierPoolCount   Number of carrier-affine slab pools active (0 if carrier-slabs are
 *                           not supported by this allocator).
 * @param leakCount          Cumulative count of {@link LoanedBuffer} instances detected as leaked
 *                           (abandoned without close()) since start. Non-zero only when
 *                           {@link #leakDetectionMode()} is {@link LeakDetectionMode#SAMPLED}
 *                           or {@link LeakDetectionMode#PARANOID}.
 * @param leakDetectionMode  The active leak detection mode for this allocator instance.
 * @see MemoryAllocator#stats()
 * @since 0.5.0
 */
public record MemoryStats(
        long totalBytes,
        long allocatedBytes,
        long freeBytes,
        long allocationCount,
        long releaseCount,
        long peakAllocatedBytes,
        int carrierPoolCount,
        long leakCount,
        LeakDetectionMode leakDetectionMode
) {

    /**
     * Compact canonical constructor with basic consistency validation.
     */
    public MemoryStats {
        if (allocatedBytes < 0 || freeBytes < 0 || peakAllocatedBytes < 0) {
            throw new IllegalArgumentException("Memory stats byte counts must be non-negative");
        }
        if (totalBytes < -1) {
            throw new IllegalArgumentException(
                    "totalBytes must be >= -1 (-1 = unknown/heap-only), got: " + totalBytes);
        }
        if (totalBytes > 0 && allocatedBytes > totalBytes) {
            throw new IllegalArgumentException(
                    "allocatedBytes (" + allocatedBytes + ") must not exceed totalBytes ("
                            + totalBytes + "); utilization() contract requires a ratio in [0.0, 1.0]");
        }
        if (carrierPoolCount < 0) {
            throw new IllegalArgumentException(
                    "carrierPoolCount must be non-negative, got: " + carrierPoolCount);
        }
        if (leakCount < 0) {
            throw new IllegalArgumentException(
                    "leakCount must be non-negative, got: " + leakCount);
        }
        Objects.requireNonNull(leakDetectionMode, "leakDetectionMode must not be null");
    }

    /**
     * Returns the utilisation ratio in range {@code [0.0, 1.0]}.
     *
     * <p>Returns {@code 0.0} (unknown) when {@code totalBytes <= 0}:
     * <ul>
     *   <li>{@code -1} — heap-only allocator with no off-heap budget (sentinel value)</li>
     *   <li>{@code  0} — allocator that does not track a total budget</li>
     * </ul>
     *
     * @return utilisation ratio, or {@code 0.0} when the total budget is unknown/absent
     */
    public double utilization() {
        if (totalBytes <= 0) {
            return 0.0;
        }
        return (double) allocatedBytes / totalBytes;
    }

    /**
     * Returns a zero-value snapshot (used as a null-object to avoid null checks).
     *
     * @return all-zero stats instance
     */
    public static MemoryStats zero() {
        return new MemoryStats(0L, 0L, 0L, 0L, 0L, 0L, 0, 0L, LeakDetectionMode.DISABLED);
    }
}
