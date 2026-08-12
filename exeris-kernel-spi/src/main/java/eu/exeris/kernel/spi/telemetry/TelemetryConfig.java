/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.spi.telemetry;

/**
 * SPI: Immutable telemetry configuration record.
 *
 * <p>Valhalla readiness: declared {@code value record} on the `preview` line (JEP&nbsp;401),
 * asserted by {@code Class::isValue} in the module's value-carrier registry test. All components
 * are primitives or immutable references.</p>
 *
 * @param consoleSinkEnabled   Whether to activate the text console sink (Community).
 * @param jfrSinkEnabled       Whether to emit JFR events (Community + Enterprise).
 * @param fileSinkPath         Optional path for file-based sink; {@code null} = disabled.
 * @param glassBoxOffHeapBytes Off-heap budget for BinaryGlassBoxSink (Enterprise).
 *                             {@code 0} = disabled (Community mode).
 * @param maxEventQueueDepth   Maximum buffered events before backpressure/drop.
 * @since 0.5.0
 */
public value record TelemetryConfig(
        boolean consoleSinkEnabled,
        boolean jfrSinkEnabled,
        String fileSinkPath,
        long glassBoxOffHeapBytes,
        int maxEventQueueDepth
) {
    private static final long MIN_OFF_HEAP_BYTES = 0L;
    private static final int MIN_EVENT_QUEUE_DEPTH = 1;

    public TelemetryConfig {
        if (glassBoxOffHeapBytes < MIN_OFF_HEAP_BYTES) {
            throw new IllegalArgumentException("glassBoxOffHeapBytes must be >= 0");
        }
        if (maxEventQueueDepth < MIN_EVENT_QUEUE_DEPTH) {
            throw new IllegalArgumentException("maxEventQueueDepth must be > 0");
        }
        if (fileSinkPath != null && fileSinkPath.isBlank()) {
            throw new IllegalArgumentException("fileSinkPath must be non-empty when provided");
        }
        if (!consoleSinkEnabled && !jfrSinkEnabled
                && (fileSinkPath == null || fileSinkPath.isBlank())
                && glassBoxOffHeapBytes == 0L) {
            throw new IllegalArgumentException("At least one telemetry sink must be enabled");
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
