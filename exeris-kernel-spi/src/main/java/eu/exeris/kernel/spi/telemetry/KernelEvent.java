/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
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
 * diagnostic paths (console / file). Enterprise {@code BinaryGlassBoxSink} reads
 * {@code rawArgs()} directly into off-heap mmap buffers.
 *
 * <h2>Valhalla Readiness</h2>
 * <p>Candidate for {@code value record} once JEP&nbsp;401 is mainline; currently a standard
 * {@code record} with reference components ({@link String}, {@link Instant},
 * {@link ExerisKernelException}).</p>
 *
 * @param code      structured error/event code (e.g. {@code "EX-MEM-1001"})
 * @param level     severity level
 * @param timestamp wall-clock capture time ({@link java.time.Instant#now()} semantics;
 *                  precision is platform-dependent and not guaranteed to be nanosecond-resolution)
 * @param exception optional attached exception (may be {@code null})
 * @param component kernel component name (compile-time constant on call site)
 * @since 0.5
 */
public record KernelEvent(
        String code,
        EventLevel level,
        Instant timestamp,
        ExerisKernelException exception,
        String component
) {
    /**
     * Factory for informational lifecycle events (bootstrap, transport bind).
     *
     * <p><strong>Allocation note:</strong> each call allocates a record instance and
     * an {@link Instant}. On truly hot-paths the sink implementation should capture
     * the timestamp itself (e.g., via epoch-nanos) to avoid per-event GC pressure.
     */
    public static KernelEvent info(String code, String component) {
        return new KernelEvent(code, EventLevel.INFO, Instant.now(), null, component);
    }

    /**
     * Factory for warning events (soft degradation, pool near exhaustion).
     *
     * @see #info(String, String) for the allocation note
     */
    public static KernelEvent warn(String code, String component, ExerisKernelException exception) {
        return new KernelEvent(code, EventLevel.WARN, Instant.now(), exception, component);
    }

    /**
     * Factory for critical error events (OOM, handshake failure, native crash).
     *
     * @see #info(String, String) for the allocation note
     */
    public static KernelEvent error(String code, String component, ExerisKernelException exception) {
        return new KernelEvent(code, EventLevel.ERROR, Instant.now(), exception, component);
    }

    /**
     * Factory for fatal events (irrecoverable kernel failure, requires process restart).
     *
     * <p>Semantically, {@code FATAL} represents conditions where the kernel can no longer
     * provide service and higher-level orchestration must perform teardown or failover.</p>
     *
     * @see #info(String, String) for the allocation note
     */
    public static KernelEvent fatal(String code, String component, ExerisKernelException exception) {
        return new KernelEvent(code, EventLevel.FATAL, Instant.now(), exception, component);
    }
}
