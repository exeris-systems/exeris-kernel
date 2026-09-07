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
 * <p><b>Allocation:</b> allocates — one record per event, and one {@link Instant} more when the
 * event is built through a factory method; the canonical constructor allocates only the record.
 * <p><b>Thread confinement:</b> any thread — every component is immutable, so an event may be
 * handed from the thread that built it to a sink running on another thread without synchronisation.
 * <p><b>Ownership:</b> nothing releasable is carried. The attached {@link ExerisKernelException} is
 * shared by reference with every sink the event reaches; it is never copied and never closed.
 *
 * @param code      structured error/event code (e.g. {@code "EX-MEM-1001"})
 * @param level     severity level
 * @param timestamp wall-clock capture time ({@link java.time.Instant#now()} semantics;
 *                  precision is platform-dependent and not guaranteed to be nanosecond-resolution)
 * @param exception optional attached exception (may be {@code null})
 * @param component kernel component name (compile-time constant on call site)
 * @apiNote Read the attached exception through {@link ExerisKernelException#rawArgs()} — never
 *          format it to a {@code String} on the hot path.
 *          {@link ExerisKernelException#getMessage()} belongs to low-frequency diagnostic paths
 *          (console, file) only.
 * @implNote The Enterprise {@code BinaryGlassBoxSink} reads {@code rawArgs()} directly into
 *           off-heap mmap buffers. This is a standard {@code record} with reference components
 *           ({@link String}, {@link Instant}, {@link ExerisKernelException}) and a candidate for a
 *           {@code value record} once JEP&nbsp;401 is mainline.
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
     * Builds an informational lifecycle event — bootstrap progress, transport bind — carrying no
     * exception and stamped with the current wall-clock time.
     *
     * @param code      structured event code recorded on the event, e.g. {@code "EX-BOOT-0001"}
     * @param component name of the kernel component reporting the event; a compile-time constant
     *                  on the call site
     * @return a new event at {@link EventLevel#INFO} whose {@link #exception()} is {@code null}
     * @apiNote Each call allocates a record instance and an {@link Instant}. On truly hot paths the
     *          sink implementation should capture the timestamp itself (e.g., via epoch-nanos) to
     *          avoid per-event GC pressure.
     */
    public static KernelEvent info(String code, String component) {
        return new KernelEvent(code, EventLevel.INFO, Instant.now(), null, component);
    }

    /**
     * Builds a warning event for soft degradation — a pool nearing exhaustion, a limit being
     * approached — stamped with the current wall-clock time.
     *
     * @param code      structured event code recorded on the event
     * @param component name of the kernel component reporting the event; a compile-time constant
     *                  on the call site
     * @param exception the kernel exception whose {@code rawArgs} carry the context; may be
     *                  {@code null}
     * @return a new event at {@link EventLevel#WARN} holding {@code exception} by reference
     * @see #info(String, String) for the allocation note
     */
    public static KernelEvent warn(String code, String component, ExerisKernelException exception) {
        return new KernelEvent(code, EventLevel.WARN, Instant.now(), exception, component);
    }

    /**
     * Builds an error event for a hard failure — memory exhaustion, handshake failure, native
     * crash — stamped with the current wall-clock time.
     *
     * @param code      structured event code recorded on the event
     * @param component name of the kernel component reporting the event; a compile-time constant
     *                  on the call site
     * @param exception the kernel exception whose {@code rawArgs} carry the context; may be
     *                  {@code null}
     * @return a new event at {@link EventLevel#ERROR} holding {@code exception} by reference
     * @see #info(String, String) for the allocation note
     */
    public static KernelEvent error(String code, String component, ExerisKernelException exception) {
        return new KernelEvent(code, EventLevel.ERROR, Instant.now(), exception, component);
    }

    /**
     * Builds a fatal event for an irrecoverable kernel failure that requires a process restart,
     * stamped with the current wall-clock time.
     *
     * <p>Semantically, {@code FATAL} represents conditions where the kernel can no longer
     * provide service and higher-level orchestration must perform teardown or failover.</p>
     *
     * @param code      structured event code recorded on the event
     * @param component name of the kernel component reporting the event; a compile-time constant
     *                  on the call site
     * @param exception the kernel exception whose {@code rawArgs} carry the context; may be
     *                  {@code null}
     * @return a new event at {@link EventLevel#FATAL} holding {@code exception} by reference
     * @see #info(String, String) for the allocation note
     */
    public static KernelEvent fatal(String code, String component, ExerisKernelException exception) {
        return new KernelEvent(code, EventLevel.FATAL, Instant.now(), exception, component);
    }
}
