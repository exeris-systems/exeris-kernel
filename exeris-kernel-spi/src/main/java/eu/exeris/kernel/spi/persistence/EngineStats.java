/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.spi.persistence;

/**
 * SPI: Immutable snapshot of {@link PersistenceEngine} pool and connection metrics.
 *
 * <h2>Valhalla Readiness</h2>
 * <p>Standard {@code record} — scalarizable by C2 JIT Escape Analysis.
 * No identity operations permitted.
 * Declared {@code value record} on the `preview` line (JEP 401); the distributed line compiles
 * the same source as an identity {@code record}, and the modifier is asserted by
 * {@code Class::isValue} in the module's value-carrier registry test.
 *
 * <h2>Zero-Allocation Telemetry</h2>
 * <p>Instances are created on the monitoring path (every 5s), not the hot path.
 * The record is immutable and stack-allocatable — no GC pressure.
 *
 * @param activeConnections Number of connections currently in use.
 * @param idleConnections   Number of connections idle in the pool.
 * @param maxConnections    Configured maximum pool size.
 * @param pendingAcquires   Number of virtual threads waiting for a connection.
 * @param totalBorrowed     Cumulative count of connections borrowed since bootstrap.
 * @param totalCreated      Cumulative count of connections physically created.
 * @param totalEvicted      Cumulative count of connections evicted (timeout/error).
 * @param tenantPoolCount   Number of active per-tenant pools (0 if per-tenant disabled).
 * @see PersistenceEngine#stats()
 * @since 0.5.0
 */
public value record EngineStats(
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
