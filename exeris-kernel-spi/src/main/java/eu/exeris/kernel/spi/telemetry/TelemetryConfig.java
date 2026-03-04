/*
 * Copyright (C) 2025-2026 Exeris. All rights reserved.
 *
 * This code is part of the Exeris Systems.
 * Distributed under the proprietary Exeris Software License.
 * Unauthorized copying or distribution is prohibited.
 */
package eu.exeris.kernel.spi.telemetry;

/**
 * SPI: Immutable telemetry configuration record.
 *
 * <p>Valhalla readiness: candidate for {@code value record} once JEP&nbsp;401 is mainline.
 * All components are primitives or immutable references, making this record suitable for
 * header-less, flattenable representation in future runtimes.</p>
 *
 * @param consoleSinkEnabled   Whether to activate the text console sink (Community).
 * @param jfrSinkEnabled       Whether to emit JFR events (Community + Enterprise).
 * @param fileSinkPath         Optional path for file-based sink; {@code null} = disabled.
 * @param blackBoxOffHeapBytes Off-heap budget for BinaryBlackBoxSink (Enterprise).
 *                             {@code 0} = disabled (Community mode).
 * @param maxEventQueueDepth   Maximum buffered events before backpressure/drop.
 * @since 0.5.0
 */
public record TelemetryConfig(
        boolean consoleSinkEnabled,
        boolean jfrSinkEnabled,
        String fileSinkPath,
        long blackBoxOffHeapBytes,
        int maxEventQueueDepth
) {
    private static final long MIN_OFF_HEAP_BYTES = 0L;
    private static final int MIN_EVENT_QUEUE_DEPTH = 1;

    public TelemetryConfig {
        if (blackBoxOffHeapBytes < MIN_OFF_HEAP_BYTES) {
            throw new IllegalArgumentException("blackBoxOffHeapBytes must be >= 0");
        }
        if (maxEventQueueDepth < MIN_EVENT_QUEUE_DEPTH) {
            throw new IllegalArgumentException("maxEventQueueDepth must be > 0");
        }
        if (fileSinkPath != null && fileSinkPath.isBlank()) {
            throw new IllegalArgumentException("fileSinkPath must be non-empty when provided");
        }
    }

    /**
     * Default configuration for development / unit tests.
     */
    public static TelemetryConfig defaults() {
        return new TelemetryConfig(true, false, null, MIN_OFF_HEAP_BYTES, 4096);
    }

    /**
     * Production community configuration: console disabled, JFR enabled, file sink active.
     */
    public static TelemetryConfig communityProduction(String logPath) {
        if (logPath == null || logPath.isBlank()) {
            throw new IllegalArgumentException("logPath must not be null or " +
                    "blank for community production configuration");
        }
        return new TelemetryConfig(false, true, logPath, MIN_OFF_HEAP_BYTES, 16_384);
    }
}
