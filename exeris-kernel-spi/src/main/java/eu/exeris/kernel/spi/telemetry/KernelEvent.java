/*
 * Copyright (C) 2025-2026 Exeris. All rights reserved.
 */
package eu.exeris.kernel.spi.telemetry;

import eu.exeris.kernel.spi.exceptions.ExerisKernelException;

import java.time.Instant;

/**
 * SPI: Immutable kernel lifecycle event passed to {@link TelemetrySink#emit}.
 *
 * <h2>Zero-Allocation Contract</h2>
 * <p>The {@code exception} field carries the raw {@code Object[]} payload via
 * {@link ExerisKernelException#rawArgs()} — never format it to a String on the hot path.
 * Community sinks may call {@link ExerisKernelException#getMessage()} only in low-frequency
 * diagnostic paths (console / file). Enterprise {@code BinaryBlackBoxSink} reads
 * {@code rawArgs()} directly into off-heap mmap buffers.
 *
 * <h2>Valhalla Readiness</h2>
 * <p>{@code value record} — no object header, all fields primitives or stable references.
 *
 * @param code       structured error/event code (e.g. {@code "EX-MEM-1001"})
 * @param level      severity level
 * @param timestamp  nanosecond-precision capture time
 * @param exception  optional attached exception (may be {@code null})
 * @param component  kernel component name (compile-time constant on call site)
 *
 * @since 0.5.0
 */
public record KernelEvent(
        String code,
        EventLevel level,
        Instant timestamp,
        ExerisKernelException exception,
        String component
) {
    /** Factory for informational lifecycle events (bootstrap, transport bind). */
    public static KernelEvent info(String code, String component) {
        return new KernelEvent(code, EventLevel.INFO, Instant.now(), null, component);
    }

    /** Factory for warning events (soft degradation, pool near exhaustion). */
    public static KernelEvent warn(String code, String component, ExerisKernelException exception) {
        return new KernelEvent(code, EventLevel.WARN, Instant.now(), exception, component);
    }

    /** Factory for critical error events (OOM, handshake failure, native crash). */
    public static KernelEvent error(String code, String component, ExerisKernelException exception) {
        return new KernelEvent(code, EventLevel.ERROR, Instant.now(), exception, component);
    }
}

