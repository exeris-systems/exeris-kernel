/*
 * Copyright (C) 2025-2026 Exeris. All rights reserved.
 *
 * This code is part of the Exeris Systems.
 * Distributed under the proprietary Exeris Software License.
 * Unauthorized copying or distribution is prohibited.
 */
package eu.exeris.kernel.spi.transport;

/**
 * SPI: Runtime diagnostics snapshot from the transport engine.
 *
 * <h2>Zero-Allocation</h2>
 * <p>This record is designed for periodic sampling (every 1–5 s) by the telemetry
 * subsystem. It is a shallow, stack-allocatable value (Valhalla-ready — future
 * {@code value record} migration is expected).
 *
 * <h2>Protocol Blindness</h2>
 * <p>Fields are protocol-agnostic. Both Community (TCP) and Enterprise (QUIC/io_uring)
 * populate the same fields. Tier-specific metrics (e.g., io_uring SQ depth,
 * QUIC loss rate) are emitted as custom JFR events, not through this record.
 *
 * @param activeConnections number of currently open connections
 * @param activeStreams      number of currently open streams across all connections
 * @param totalAccepted      cumulative number of connections accepted since engine start
 * @param totalRejected      cumulative number of connections/streams rejected (load shedding)
 * @param rttP50Micros       median RTT in microseconds (0 if no samples)
 * @param rttP95Micros       95th percentile RTT in microseconds (0 if no samples)
 * @since 0.5.0
 * @see TransportEngine#stats()
 */
public record TransportStats(
        int activeConnections,
        long activeStreams,
        long totalAccepted,
        long totalRejected,
        long rttP50Micros,
        long rttP95Micros
) {

    /** Empty stats — returned when the engine has not started yet. */
    public static final TransportStats EMPTY = new TransportStats(0, 0, 0, 0, 0, 0);
}

