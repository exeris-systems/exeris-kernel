/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.core.telemetry;

import eu.exeris.kernel.core.telemetry.jfr.TelemetryJfrEvents.CarrierPinnedJfrEvent;
import eu.exeris.kernel.core.telemetry.jfr.TelemetryJfrEvents.EventEngineJfrEvent;
import eu.exeris.kernel.core.telemetry.jfr.TelemetryJfrEvents.KernelLatencyJfrEvent;
import eu.exeris.kernel.core.telemetry.jfr.TelemetryJfrEvents.KernelLifecycleJfrEvent;
import eu.exeris.kernel.core.telemetry.jfr.TelemetryJfrEvents.KernelMetricJfrEvent;
import eu.exeris.kernel.core.telemetry.jfr.TelemetryJfrEvents.MemoryExhaustionJfrEvent;
import eu.exeris.kernel.core.telemetry.jfr.TelemetryJfrEvents.TransportBindJfrEvent;
import eu.exeris.kernel.spi.exceptions.ExerisKernelException;
import eu.exeris.kernel.spi.exceptions.KernelErrorCodes;
import eu.exeris.kernel.spi.telemetry.KernelEvent;
import eu.exeris.kernel.spi.telemetry.TelemetrySink;
import jdk.jfr.FlightRecorder;

/**
 * Community: Strongly-typed JFR telemetry sink.
 *
 * <h2>Allocation contract (Community tier)</h2>
 * <p>One {@code new Event()} per emission — the JFR framework copies fields into
 * its off-heap recording buffer on {@code commit()} and the event object is
 * immediately eligible for GC. Per {@code telemetry.md}: <em>"the single
 * allocation here is acceptable — exceptions are never on the hot-path"</em>.
 *
 * <h2>Thread safety</h2>
 * <p>Per {@code telemetry.md}: JFR {@code Event} instances are <strong>not
 * thread-safe</strong> and must never be shared across threads. Each emission
 * allocates its own instance.
 *
 * <h2>Hot-path zero-alloc gate</h2>
 * <p>Every emit/metric path opens with {@code FlightRecorder.isInitialized()} — a single
 * static boolean read that returns {@code false} until the JFR subsystem is first activated.
 * This prevents any {@code new Event()} allocation when JFR has never been started in the JVM.
 * A secondary {@code jfr.isEnabled()} check guards individual event-type population once JFR
 * is active but the specific event type is disabled by the active configuration.
 *
 * <h2>Tier boundary</h2>
 * <p>Zero-GC emission belongs to the Enterprise {@code BinaryBlackBoxSink} which
 * writes fixed-width crash-log structs to an off-heap ring buffer via
 * {@code MemorySegment}. This sink is Community — bounded allocations, JFR-only.
 *
 * <h2>The Wall (SPI compliance)</h2>
 * <p>Depends only on {@code exeris-kernel-spi} interfaces and
 * {@code eu.exeris.kernel.core.telemetry.jfr} event types. Zero knowledge of
 * io_uring, JDBC, Netty, or any transport driver.
 *
 * @since 0.5.0
 */
// TooManyMethods: TelemetrySink SPI mandates emit/increment/gauge/latency/sinkName/close.
// Typed private emitters are a minimal O(1) dispatch table required by the typed-JFR contract.
// CyclomaticComplexity: dispatchTyped() switch covers domain prefixes — irreducible minimum.
// GodClass: ATFD and WMC driven by typed dispatch table growth (one emitter per domain prefix).
// Splitting into domain-specific sub-sinks would require a coordinator class of equal complexity.
@SuppressWarnings({"PMD.TooManyMethods", "PMD.CyclomaticComplexity", "PMD.GodClass"})
public final class JfrTelemetrySink implements TelemetrySink {

    private static final String SINK_NAME = "ExerisCore/JfrTelemetrySink";

    private volatile boolean closed;

    // =========================================================================
    // TelemetrySink — emit
    // =========================================================================

    @Override
    public void emit(KernelEvent event) {
        if (closed || !FlightRecorder.isInitialized()) {
            return;
        }
        ExerisKernelException exception = event.exception();
        if (exception != null) {
            dispatchTyped(event, exception);
        } else {
            emitLifecycle(event, "");
        }
    }

    private void dispatchTyped(KernelEvent event, ExerisKernelException exception) {
        String code = event.code();
        if (code == null || code.isEmpty()) {
            emitLifecycle(event, safeMessage(exception));
            return;
        }
        if (code.startsWith("EX-MEM-")) {
            emitMemoryExhaustion(event, exception, code);
        } else if (code.startsWith("EX-NET-")) {
            emitTransportBind(event, exception, code);
        } else if (code.startsWith("EX-RUN-")) {
            emitCarrierPinned(event, exception, code);
        } else if (code.startsWith("EX-EVENT-")) {
            emitEventEngine(event, exception, code);
        } else {
            emitLifecycle(event, safeMessage(exception));
        }
    }

    // =========================================================================
    // Typed emitters
    // =========================================================================

    private static void emitMemoryExhaustion(KernelEvent event, ExerisKernelException exception, String code) {
        if (!KernelErrorCodes.EX_MEM_1001.equals(code)) {
            emitLifecycle(event, safeMessage(exception));
            return;
        }
        MemoryExhaustionJfrEvent jfr = new MemoryExhaustionJfrEvent();
        if (!jfr.isEnabled()) {
            return;
        }
        Object[] args = exception.rawArgs();
        jfr.errorCode = code;
        jfr.requestedBytes = rawLong(args, 0);
        jfr.availableBytes = rawLong(args, 1);
        jfr.component = event.component();
        jfr.commit();
    }

    private static void emitTransportBind(KernelEvent event, ExerisKernelException exception, String code) {
        if (!KernelErrorCodes.EX_NET_4001.equals(code) && !KernelErrorCodes.EX_NET_4005.equals(code)) {
            emitLifecycle(event, safeMessage(exception));
            return;
        }
        TransportBindJfrEvent jfr = new TransportBindJfrEvent();
        if (!jfr.isEnabled()) {
            return;
        }
        Object[] args = exception.rawArgs();
        jfr.errorCode = code;
        jfr.transportName = rawString(args, 0);
        jfr.port = (int) rawLong(args, 1);
        jfr.component = event.component();
        jfr.commit();
    }

    private static void emitCarrierPinned(KernelEvent event, ExerisKernelException exception, String code) {
        if (!KernelErrorCodes.EX_RUN_3002.equals(code)) {
            emitLifecycle(event, safeMessage(exception));
            return;
        }
        CarrierPinnedJfrEvent jfr = new CarrierPinnedJfrEvent();
        if (!jfr.isEnabled()) {
            return;
        }
        Object[] args = exception.rawArgs();
        jfr.errorCode = code;
        jfr.blockTimeMs = rawLong(args, 0);
        jfr.carrierThreadName = rawString(args, 1);
        jfr.component = event.component();
        jfr.commit();
    }

    private static void emitEventEngine(KernelEvent event, ExerisKernelException exception, String code) {
        EventEngineJfrEvent jfr = new EventEngineJfrEvent();
        if (!jfr.isEnabled()) {
            return;
        }
        Object[] args = exception.rawArgs();
        jfr.errorCode  = code;
        String firstString = firstStringArg(args);
        jfr.engineName = firstString != null ? firstString : event.component();
        jfr.rawArg0    = rawString(args, 0);
        jfr.rawArg1    = rawString(args, 1);
        jfr.commit();
    }

    private static void emitLifecycle(KernelEvent event, String message) {
        KernelLifecycleJfrEvent jfr = new KernelLifecycleJfrEvent();
        if (!jfr.isEnabled()) {
            return;
        }
        jfr.errorCode = event.code();
        jfr.level = event.level().name();
        jfr.component = event.component();
        jfr.message = message;
        jfr.commit();
    }

    // =========================================================================
    // TelemetrySink — metrics
    // =========================================================================

    @Override
    public void increment(String name, long delta) {
        if (closed || !FlightRecorder.isInitialized()) {
            return;
        }
        KernelMetricJfrEvent jfr = new KernelMetricJfrEvent();
        if (!jfr.isEnabled()) {
            return;
        }
        jfr.metricName = name;
        jfr.metricType = "COUNTER";
        jfr.value = delta;
        jfr.commit();
    }

    @Override
    public void gauge(String name, long value) {
        if (closed || !FlightRecorder.isInitialized()) {
            return;
        }
        KernelMetricJfrEvent jfr = new KernelMetricJfrEvent();
        if (!jfr.isEnabled()) {
            return;
        }
        jfr.metricName = name;
        jfr.metricType = "GAUGE";
        jfr.value = value;
        jfr.commit();
    }

    @Override
    public void latency(String name, long nanoseconds) {
        if (closed || !FlightRecorder.isInitialized()) {
            return;
        }
        KernelLatencyJfrEvent jfr = new KernelLatencyJfrEvent();
        if (!jfr.isEnabled()) {
            return;
        }
        jfr.metricName = name;
        jfr.nanoseconds = nanoseconds;
        jfr.commit();
    }

    // =========================================================================
    // TelemetrySink — identity / lifecycle
    // =========================================================================

    @Override
    public String sinkName() {
        return SINK_NAME;
    }

    @Override
    public void close() {
        closed = true;
    }

    // =========================================================================
    // rawArgs helpers
    // =========================================================================

    /**
     * Extracts a {@code long} from {@code rawArgs[index]}.
     * Handles widening for {@link Long}, {@link Integer}, {@link Short}, {@link Byte}.
     * Returns {@code -1L} for missing, {@code null}, or unrecognised element types.
     */
    private static long rawLong(Object[] args, int index) {
        if (args == null || index >= args.length || args[index] == null) {
            return -1L;
        }
        return switch (args[index]) {
            case Long longVal -> longVal;
            case Integer intVal -> intVal;
            case Short shortVal -> shortVal;
            case Byte byteVal -> byteVal;
            default -> -1L;
        };
    }

    /**
     * Extracts a {@code String} from {@code rawArgs[index]}.
     * Returns {@code ""} for missing, {@code null}, or non-{@code String} elements.
     * Never calls {@code toString()} — no hidden allocation.
     */
    private static String rawString(Object[] args, int index) {
        if (args == null || index >= args.length) {
            return "";
        }
        return args[index] instanceof String str ? str : "";
    }

    /**
     * Returns the first non-blank {@code String} element in {@code rawArgs}, or {@code null}
     * if none exists. Used for EX-EVENT-* paths where the primary identifier is a String.
     */
    private static String firstStringArg(Object[] args) {
        if (args == null) {
            return null;
        }
        for (Object arg : args) {
            if (arg instanceof String value && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private static String safeMessage(ExerisKernelException exception) {
        String msg = exception.getMessage();
        return msg != null ? msg : "";
    }
}
