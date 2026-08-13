/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
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
 * <p>Declared {@code value record} on the `preview` line (JEP 401, preview in JDK 28). The
 * distributed line compiles the same source as an identity {@code record}; the modifier is the
 * only difference, and it is asserted by {@code Class::isValue} in the module's value-carrier
 * registry test rather than left to a one-time inspection.
 * The components are references ({@link String}, {@link Instant},
 * {@link ExerisKernelException}), so instances are not flattenable; the modifier removes
 * identity, which is what callers must not depend on.</p>
 *
 * @param code      structured error/event code (e.g. {@code "EX-MEM-1001"})
 * @param level     severity level
 * @param timestamp wall-clock capture time ({@link java.time.Instant#now()} semantics;
 *                  precision is platform-dependent and not guaranteed to be nanosecond-resolution)
 * @param exception optional attached exception (may be {@code null})
 * @param component kernel component name (compile-time constant on call site)
 * @since 0.5.0
 */
public value record KernelEvent(
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
