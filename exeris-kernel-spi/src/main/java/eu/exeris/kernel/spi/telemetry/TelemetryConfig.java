/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.spi.telemetry;

/**
 * SPI: Immutable telemetry configuration record.
 *
 * <p>A {@link TelemetryProvider} reads it once, at bootstrap, to decide which sinks to build and
 * what each may consume; it is rejected at construction unless it leaves at least one sink active.
 *
 * @param consoleSinkEnabled   Whether to activate the text console sink (Community).
 * @param jfrSinkEnabled       Whether to emit JFR events (Community + Enterprise).
 * @param fileSinkPath         Optional path for file-based sink; {@code null} = disabled.
 * @param glassBoxOffHeapBytes Off-heap budget for BinaryGlassBoxSink (Enterprise).
 *                             {@code 0} = disabled (Community mode).
 * @param maxEventQueueDepth   Maximum buffered events before backpressure/drop.
 * @implNote All components are primitives or immutable references, making this record a candidate
 *           for a header-less, flattenable {@code value record} once JEP&nbsp;401 is mainline.
 * @since 0.5
 */
public record TelemetryConfig(
        boolean consoleSinkEnabled,
        boolean jfrSinkEnabled,
        String fileSinkPath,
        long glassBoxOffHeapBytes,
        int maxEventQueueDepth
) {
    private static final long MIN_OFF_HEAP_BYTES = 0L;
    private static final int MIN_EVENT_QUEUE_DEPTH = 1;

    /**
     * Validates the budgets and rejects a configuration that would leave the kernel with no
     * telemetry at all.
     *
     * @throws IllegalArgumentException if {@code glassBoxOffHeapBytes} is negative, if
     *                                  {@code maxEventQueueDepth} is not at least {@code 1}, if
     *                                  {@code fileSinkPath} is non-{@code null} but blank, or if
     *                                  no sink is enabled by the combination of components
     */
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
     * Returns the development and unit-test shape: console sink on, JFR off, no file sink, no
     * off-heap Glass-Box budget, and room for 4096 buffered events.
     *
     * @return a configuration whose only active sink is the console sink
     */
    public static TelemetryConfig defaults() {
        return new TelemetryConfig(true, false, null, MIN_OFF_HEAP_BYTES, 4096);
    }

    /**
     * Returns the Community production shape: console off, JFR on, a file sink writing to
     * {@code logPath}, no off-heap Glass-Box budget, and room for 16&nbsp;384 buffered events.
     *
     * @param logPath filesystem path the file sink writes to; must be neither {@code null} nor
     *                blank, because this shape has no console fallback
     * @return a configuration whose active sinks are the JFR sink and a file sink at {@code logPath}
     * @throws IllegalArgumentException if {@code logPath} is {@code null} or blank
     */
    public static TelemetryConfig communityProduction(String logPath) {
        if (logPath == null || logPath.isBlank()) {
            throw new IllegalArgumentException("logPath must not be null or " +
                    "blank for community production configuration");
        }
        return new TelemetryConfig(false, true, logPath, MIN_OFF_HEAP_BYTES, 16_384);
    }
}
