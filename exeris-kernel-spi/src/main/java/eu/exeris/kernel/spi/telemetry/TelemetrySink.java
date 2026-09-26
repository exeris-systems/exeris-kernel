/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.spi.telemetry;


/**
 * SPI: Pluggable telemetry output channel (sink).
 *
 * <h2>The Wall (SPI Compliance)</h2>
 * <p>This interface has zero knowledge of file paths, JFR event types, or binary
 * off-heap structures. Community and Enterprise implementations live behind this wall.
 *
 * <p><b>Allocation:</b> zero-alloc on hot path for {@link #increment}, {@link #gauge} and
 * {@link #latency} — their arguments are a constant name and a primitive; {@link #emit} adds no
 * {@code String} formatting, so an emission costs the {@link KernelEvent} the caller already built
 * plus whatever carrier the sink's own output channel needs.
 * <p><b>Thread confinement:</b> any thread — one sink instance is built at bootstrap and shared by
 * every emitting subsystem, so calls arrive from arbitrary kernel threads, and behind an
 * asynchronous dispatcher from its consumer virtual thread rather than from the producer.
 * <p><b>Ownership:</b> whoever obtained the sink from
 * {@link TelemetryProvider#createSinks(TelemetryConfig)} closes it; the sink owns the handles and
 * buffers it opened and releases them in {@link #close()}.
 *
 * @implSpec An implementation must accept every {@link EventLevel}, and a {@link KernelEvent} whose
 *           {@link KernelEvent#exception()} is {@code null}, without throwing; must make
 *           {@link #close()} idempotent; must drop events emitted after {@code close()} silently
 *           rather than throwing; and must return a non-blank {@link #sinkName()}.
 * @implNote Community ships {@code ConsoleSink}, {@code FileSink}, {@code Slf4jTelemetrySink} and a
 *           Flight Recorder sink — text and JFR output. The Enterprise {@code BinaryGlassBoxSink}
 *           dumps {@link eu.exeris.kernel.spi.exceptions.ExerisKernelException#rawArgs()} straight
 *           into an off-heap mmap region, with zero {@code String} allocation on the critical path.
 * @since 0.5
 * @see TelemetryProvider
 */
public interface TelemetrySink extends AutoCloseable {

    /**
     * Records a kernel event on this sink's output channel.
     *
     * @param event the kernel event to record; never {@code null}
     * @implSpec After {@link #close()} the call is a silent no-op.
     * @implNote The Core {@code JfrTelemetrySink}'s typed {@code EX-MEM-}/{@code EX-NET-}/
     *           {@code EX-RUN-} fast paths read
     *           {@link eu.exeris.kernel.spi.exceptions.ExerisKernelException#rawArgs()} directly
     *           and never call {@code getMessage()}; its fallback path and Community's diagnostic
     *           sinks ({@code ConsoleSink}, {@code FileSink}, {@code Slf4jTelemetrySink}) call
     *           {@link eu.exeris.kernel.spi.exceptions.ExerisKernelException#getMessage()} and
     *           allocate {@code String}s by design, since those are cold, low-frequency paths
     *           rather than the hot path the zero-allocation goal targets.
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
     * @param name        metric name
     * @param nanoseconds latency sample
     */
    void latency(String name, long nanoseconds);

    /**
     * Returns the name under which this sink appears in bootstrap diagnostics and in the records of
     * events dropped on its behalf.
     *
     * @return a non-null, non-blank name that identifies this sink uniquely and does not change
     *         while it is open, such as {@code "ExerisCore/JfrTelemetrySink"}
     */
    String sinkName();

    /**
     * Releases the output channel and every handle or buffer this sink opened.
     *
     * <p>Narrows {@link AutoCloseable#close()}: closing a sink throws no checked exception.
     *
     * @implSpec Must be idempotent — a second call does nothing and does not throw — and must leave
     *           the sink in a state where {@link #emit} drops silently.
     */
    @Override
    void close();
}
