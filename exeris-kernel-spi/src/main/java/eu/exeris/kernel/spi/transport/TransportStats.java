/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.spi.transport;

/**
 * SPI: Runtime diagnostics snapshot from the transport engine.
 *
 * <h2>Zero-Allocation</h2>
 * <p>This record is designed for periodic sampling (every 1–5 s) by the telemetry
 * subsystem. It is a small, shallow data carrier, designed to be Valhalla-ready
 * (future {@code value record} migration is expected once JEP 401 is mainline,
 * at which point instances will scalarise on hot paths via JIT Escape Analysis).
 *
 * <h2>Protocol Blindness</h2>
 * <p>Fields are protocol-agnostic. Both Community and Enterprise transport bindings
 * populate the same fields. Tier-specific metrics (e.g., ring depth, loss rate)
 * are emitted as custom JFR events, not through this record.
 *
 * @param activeConnections number of currently open connections
 * @param activeStreams     number of currently open streams across all connections
 *                          ({@code long} — can exceed {@code Integer.MAX_VALUE} on
 *                          high-throughput multiplexed transports)
 * @param totalAccepted     cumulative number of connections accepted since engine start
 * @param totalRejected     cumulative work the engine declined, across <em>every</em> refusal path
 *                          it has — priority-aware stream shedding and any admission ceiling
 *                          enforced before a shed decision is reached, such as a cap on concurrent
 *                          connections applied at accept time. A driver that counts only one of its
 *                          paths reports zero during a total refusal, which is read as evidence the
 *                          fault lies elsewhere; the contract is the sum, not whichever mechanism a
 *                          binding happened to wire up.
 * @param rttP50Micros      median RTT in microseconds (0 if no samples)
 * @param rttP95Micros      95th percentile RTT in microseconds (0 if no samples)
 * @see TransportEngine#stats()
 * @since 0.5.0
 */
public record TransportStats(
        int activeConnections,
        long activeStreams,
        long totalAccepted,
        long totalRejected,
        long rttP50Micros,
        long rttP95Micros
) {

    /**
     * Empty stats — returned when the engine has not started yet.
     */
    public static final TransportStats EMPTY = new TransportStats(0, 0, 0, 0, 0, 0);
}
