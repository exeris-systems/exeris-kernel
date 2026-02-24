/*
 * Copyright (C) 2025-2026 Exeris. All rights reserved.
 *
 * This code is part of the Exeris Systems.
 * Distributed under the proprietary Exeris Software License.
 * Unauthorized copying or distribution is prohibited.
 */
package eu.exeris.kernel.spi.telemetry;


/**
 * SPI: Pluggable telemetry output channel (sink).
 *
 * <h2>The Wall (SPI Compliance)</h2>
 * <p>This interface has zero knowledge of file paths, JFR event types, or binary
 * off-heap structures. Community and Enterprise implementations live behind this wall.
 *
 * <h2>Tier Implementations</h2>
 * <ul>
 *   <li><b>Community</b>: {@code ConsoleSink}, {@code FileSink}, {@code JfrSink} — text/JFR.</li>
 *   <li><b>Enterprise</b>: {@code BinaryBlackBoxSink} — direct off-heap mmap dump of
 *       {@link eu.exeris.kernel.spi.exceptions.ExerisKernelException#rawArgs()}.
 *       Zero String allocation on the critical path.</li>
 * </ul>
 *
 * @since 0.5.0
 * @see TelemetryProvider
 */
public interface TelemetrySink extends AutoCloseable {

    /**
     * Emits a kernel event to this sink.
     *
     * <p><b>Hot-path contract</b>: implementations MUST NOT allocate {@code String} objects
     * by calling
     * {@link eu.exeris.kernel.spi.exceptions.ExerisKernelException#getMessage()} or similar.
     * They MUST read
     * {@link eu.exeris.kernel.spi.exceptions.ExerisKernelException#rawArgs()}
     * directly for zero-copy serialization.
     *
     * @param event the kernel event to record; never {@code null}
     */
    void emit(KernelEvent event);

    /**
     * Emits a counter increment metric.
     *
     * @param name  metric name (must be a compile-time constant on the call site)
     * @param delta increment value (usually {@code 1})
     */
    void increment(String name, long delta);

    /**
     * Sets a gauge metric to an absolute value.
     *
     * @param name  metric name
     * @param value new gauge value
     */
    void gauge(String name, long value);

    /**
     * Records a latency sample in nanoseconds.
     *
     * @param name         metric name
     * @param nanoseconds  latency sample
     */
    void latency(String name, long nanoseconds);

    /** Returns the unique name of this sink (used in bootstrap diagnostics). */
    String sinkName();

    @Override
    void close();
}

