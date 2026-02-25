/*
 * Copyright (C) 2025-2026 Exeris. All rights reserved.
 *
 * This code is part of the Exeris Systems.
 * Distributed under the proprietary Exeris Software License.
 * Unauthorized copying or distribution is prohibited.
 */
package eu.exeris.kernel.spi.persistence;

/**
 * SPI: Immutable snapshot of {@link PersistenceEngine} pool and connection metrics.
 *
 * <h2>Valhalla Readiness</h2>
 * <p>Standard {@code record} — scalarizable by C2 JIT Escape Analysis.
 * No identity operations permitted. Will migrate to {@code value record} (JEP 401).
 *
 * <h2>Zero-Allocation Telemetry</h2>
 * <p>Instances are created on the monitoring path (every 5s), not the hot path.
 * The record is immutable and stack-allocatable — no GC pressure.
 *
 * @param activeConnections   Number of connections currently in use.
 * @param idleConnections     Number of connections idle in the pool.
 * @param maxConnections      Configured maximum pool size.
 * @param pendingAcquires     Number of virtual threads waiting for a connection.
 * @param totalBorrowed       Cumulative count of connections borrowed since bootstrap.
 * @param totalCreated        Cumulative count of connections physically created.
 * @param totalEvicted        Cumulative count of connections evicted (timeout/error).
 * @param tenantPoolCount     Number of active per-tenant pools (0 if per-tenant disabled).
 *
 * @since 0.5.0
 * @see PersistenceEngine#stats()
 */
public record EngineStats(
        int activeConnections,
        int idleConnections,
        int maxConnections,
        int pendingAcquires,
        long totalBorrowed,
        long totalCreated,
        long totalEvicted,
        int tenantPoolCount
) {

    /**
     * Pool saturation ratio: {@code activeConnections / maxConnections}.
     *
     * @return saturation in {@code [0.0, 1.0]}
     */
    public double saturation() {
        return maxConnections > 0 ? (double) activeConnections / maxConnections : 0.0;
    }

    /**
     * Returns {@code true} if pool saturation exceeds the given threshold.
     *
     * @param threshold saturation threshold (e.g., 0.80)
     * @return {@code true} if saturated
     */
    public boolean isSaturated(double threshold) {
        return saturation() >= threshold;
    }

    /**
     * Empty stats for bootstrap / pre-init state.
     */
    public static EngineStats empty() {
        return new EngineStats(0, 0, 0, 0, 0L, 0L, 0L, 0);
    }
}

