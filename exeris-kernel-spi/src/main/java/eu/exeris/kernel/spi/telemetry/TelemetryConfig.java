/*
 * Copyright (C) 2025-2026 Exeris. All rights reserved.
 */
package eu.exeris.kernel.spi.telemetry;

/**
 * SPI: Immutable telemetry configuration record.
 *
 * @param consoleSinkEnabled   Whether to activate the text console sink (Community).
 * @param jfrSinkEnabled       Whether to emit JFR events (Community + Enterprise).
 * @param fileSinkPath         Optional path for file-based sink; {@code null} = disabled.
 * @param blackBoxOffHeapBytes Off-heap budget for BinaryBlackBoxSink (Enterprise).
 *                             {@code 0} = disabled (Community mode).
 * @param maxEventQueueDepth   Maximum buffered events before backpressure/drop.
 *
 * @since 0.5.0
 */
public record TelemetryConfig(
        boolean consoleSinkEnabled,
        boolean jfrSinkEnabled,
        String fileSinkPath,
        long blackBoxOffHeapBytes,
        int maxEventQueueDepth
) {
    /** Default configuration for development / unit tests. */
    public static TelemetryConfig defaults() {
        return new TelemetryConfig(true, false, null, 0L, 4096);
    }

    /** Production community configuration: JFR + console disabled, file sink active. */
    public static TelemetryConfig communityProduction(String logPath) {
        return new TelemetryConfig(false, true, logPath, 0L, 16_384);
    }
}

