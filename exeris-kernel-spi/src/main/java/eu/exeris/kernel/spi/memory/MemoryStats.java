/*
 * Copyright (C) 2025-2026 Exeris. All rights reserved.
 *
 * This code is part of the Exeris Systems.
 * Distributed under the proprietary Exeris Software License.
 * Unauthorized copying or distribution is prohibited.
 */
package eu.exeris.kernel.spi.memory;

/**
 * Immutable snapshot of {@link MemoryAllocator} diagnostics.
 *
 * <h2>Valhalla Readiness</h2>
 * <p>Declared as a {@code value record} (JEP 401). The JVM eliminates the object
 * header when arrays of {@code MemoryStats} are created; fields are primitives only,
 * enabling full heap flattening with zero pointer indirection.
 *
 * <h2>Zero-Allocation Contract</h2>
 * <p>This record is returned by {@link MemoryAllocator#stats()} on the diagnostic path.
 * It is intentionally <em>not</em> on the hot allocation path, so the record allocation
 * per call is acceptable (diagnostics are low-frequency).
 *
 * @param totalBytes          Total off-heap budget configured for this allocator (bytes).
 * @param allocatedBytes      Bytes currently in use (live loaned buffers).
 * @param freeBytes           Bytes available for new allocations.
 * @param allocationCount     Cumulative count of all {@code allocate*} calls since start.
 * @param releaseCount        Cumulative count of buffers returned to the pool (refCount → 0).
 * @param peakAllocatedBytes  Historical maximum of {@code allocatedBytes} observed.
 * @param carrierPoolCount    Number of carrier-affine slab pools active (0 if carrier-slabs are
 *                            not supported by this allocator).
 *
 * @since 0.5.0
 * @see MemoryAllocator#stats()
 */
public record MemoryStats(
        long totalBytes,
        long allocatedBytes,
        long freeBytes,
        long allocationCount,
        long releaseCount,
        long peakAllocatedBytes,
        int  carrierPoolCount
) {

    /** Compact canonical constructor with basic consistency validation. */
    public MemoryStats {
        if (allocatedBytes < 0 || freeBytes < 0 || peakAllocatedBytes < 0) {
            throw new IllegalArgumentException("Memory stats byte counts must be non-negative");
        }
    }

    /**
     * Returns the utilisation ratio in range {@code [0.0, 1.0]}.
     *
     * <p>Result is {@code 0.0} when {@code totalBytes == 0} (heap-only allocators
     * that do not track total budget).
     *
     * @return utilisation ratio
     */
    public double utilization() {
        if (totalBytes == 0) {
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
        return new MemoryStats(0L, 0L, 0L, 0L, 0L, 0L, 0);
    }
}


