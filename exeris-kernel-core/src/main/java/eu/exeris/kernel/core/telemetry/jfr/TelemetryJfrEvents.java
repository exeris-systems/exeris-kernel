/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.core.telemetry.jfr;

import jdk.jfr.Category;
import jdk.jfr.Description;
import jdk.jfr.Event;
import jdk.jfr.Label;
import jdk.jfr.Name;
import jdk.jfr.StackTrace;

// AvoidDuplicateLiterals: JFR @Label / @Category / @Name are annotation literals —
// cannot be extracted to constants. The @Category array elements ("Exeris Kernel", etc.)
// repeat across inner event classes by design.
@SuppressWarnings("PMD.AvoidDuplicateLiterals")
public final class TelemetryJfrEvents {

    private TelemetryJfrEvents() {
    }

    /**
     * Returns the canonical container class name for JFR event category grouping.
     * Required by PMD {@code MissingStaticMethodInNonInstantiatableClass} — utility
     * classes must expose at least one static accessor.
     *
     * @return fully qualified class name of this utility container
     */
    public static String containerName() {
        return TelemetryJfrEvents.class.getName();
    }

    // =========================================================================
    // KernelLifecycleJfrEvent — maps KernelEvent INFO/WARN/ERROR/FATAL
    // =========================================================================

    /**
     * Strongly-typed JFR event for kernel lifecycle transitions.
     *
     * <p>Emitted by {@link eu.exeris.kernel.core.telemetry.JfrTelemetrySink} for every
     * {@link eu.exeris.kernel.spi.telemetry.KernelEvent} routed through the Core sink.
     *
     * <p>{@code @StackTrace(false)} — suppresses stack trace capture; JFR uses O(depth)
     * allocation per captured frame, banned per {@code performance-contract.md}.
     */
    @Name("eu.exeris.kernel.telemetry.KernelLifecycle")
    @Label("Kernel Lifecycle Event")
    @Description("Emitted for kernel bootstrap, subsystem lifecycle, warning, and error events")
    @Category({"Exeris Kernel", "Telemetry"})
    @StackTrace(false)
    public static final class KernelLifecycleJfrEvent extends Event {
        /**
         * Structured {@code EX-[DOMAIN]-[ID]} error code.
         */
        @Label("Error Code")
        public String errorCode;

        /**
         * Severity level name — {@code INFO}, {@code WARN}, {@code ERROR}, {@code FATAL}.
         */
        @Label("Level")
        public String level;

        /**
         * Kernel component that emitted the event (compile-time constant on call site).
         */
        @Label("Component")
        public String component;

        /**
         * Exception message — populated ONLY when JFR recording is active.
         * MUST NOT be populated on hot paths where {@code isEnabled()} returns {@code false}.
         */
        @Label("Message")
        public String message;
    }

    // =========================================================================
    // TransportBindJfrEvent — EX-NET-4001 / EX-NET-4005
    // =========================================================================

    /**
     * Strongly-typed JFR event for transport bind and engine-start lifecycle.
     *
     * <p>Emitted on {@link eu.exeris.kernel.spi.exceptions.transport.TransportException}
     * with code {@code EX-NET-4001} (bind failure) or {@code EX-NET-4005} (start failure).
     * Fields map directly to the {@code rawArgs} binary layout documented in
     * {@code TransportException}.
     */
    @Name("eu.exeris.kernel.telemetry.TransportBind")
    @Label("Transport Bind / Start")
    @Description("Emitted on transport bind or engine-start — maps to EX-NET-4001 / EX-NET-4005")
    @Category({"Exeris Kernel", "Transport"})
    @StackTrace(false)
    public static final class TransportBindJfrEvent extends Event {
        @Label("Error Code")
        public String errorCode;

        @Label("Transport Name")
        public String transportName;

        /**
         * Port number; {@code -1} if not applicable.
         */
        @Label("Port")
        public int port;

        @Label("Component")
        public String component;
    }

    // =========================================================================
    // CarrierPinnedJfrEvent — EX-RUN-3002
    // =========================================================================

    /**
     * Strongly-typed JFR event for virtual-thread carrier pinning.
     *
     * <p>{@code @StackTrace(false)} is explicitly overridden here; per {@code telemetry.md}
     * stack traces for pinning events are captured by the JVM's built-in
     * {@code jdk.VirtualThreadPinned} event — the JFR Pinning Monitor reads that stream.
     * Duplicating the capture here would add O(depth) allocation per pinning event.
     *
     * <p>rawArgs layout: index 0 = {@code long blockTimeMs}, index 1 = {@code String carrierName}.
     */
    @Name("eu.exeris.kernel.telemetry.CarrierPinned")
    @Label("Carrier Thread Pinned")
    @Description("Emitted when a virtual-thread carrier is blocked beyond the kill threshold — EX-RUN-3002")
    @Category({"Exeris Kernel", "Runtime"})
    @StackTrace(false)
    public static final class CarrierPinnedJfrEvent extends Event {
        @Label("Error Code")
        public String errorCode;

        @Label("Block Time (ms)")
        public long blockTimeMs;

        @Label("Carrier Thread Name")
        public String carrierThreadName;

        @Label("Component")
        public String component;
    }

    // =========================================================================
    // MemoryExhaustionJfrEvent — EX-MEM-1001
    // =========================================================================

    /**
     * Strongly-typed JFR event for off-heap exhaustion.
     *
     * <p>rawArgs layout: index 0 = {@code long requestedBytes}, index 1 = {@code long availableBytes}.
     */
    @Name("eu.exeris.kernel.telemetry.MemoryExhaustion")
    @Label("Memory Exhaustion")
    @Description("Emitted on every MemoryExhaustedException — EX-MEM-1001")
    @Category({"Exeris Kernel", "Memory"})
    @StackTrace(false)
    public static final class MemoryExhaustionJfrEvent extends Event {
        @Label("Error Code")
        public String errorCode;

        @Label("Requested Bytes")
        public long requestedBytes;

        @Label("Available Bytes")
        public long availableBytes;

        @Label("Component")
        public String component;
    }

    // =========================================================================
    // KernelMetricJfrEvent — counter / gauge
    // =========================================================================

    /**
     * Strongly-typed JFR event for counter increments and gauge updates.
     *
     * <p>Type discriminator: {@code "COUNTER"} for {@code increment()} calls,
     * {@code "GAUGE"} for {@code gauge()} calls.
     */
    @Name("eu.exeris.kernel.telemetry.KernelMetric")
    @Label("Kernel Metric")
    @Description("Counter increment or gauge update emitted via Core JFR sink")
    @Category({"Exeris Kernel", "Telemetry"})
    @StackTrace(false)
    public static final class KernelMetricJfrEvent extends Event {
        @Label("Metric Name")
        public String metricName;

        /**
         * {@code "COUNTER"} or {@code "GAUGE"}.
         */
        @Label("Type")
        public String metricType;

        @Label("Value")
        public long value;
    }

    // =========================================================================
    // KernelLatencyJfrEvent — nanosecond-resolution latency sample
    // =========================================================================

    /**
     * Strongly-typed JFR event for nanosecond-resolution latency samples.
     */
    @Name("eu.exeris.kernel.telemetry.KernelLatency")
    @Label("Kernel Latency Sample")
    @Description("Latency sample in nanoseconds emitted via Core JFR sink")
    @Category({"Exeris Kernel", "Telemetry"})
    @StackTrace(false)
    public static final class KernelLatencyJfrEvent extends Event {
        @Label("Metric Name")
        public String metricName;

        @Label("Duration (ns)")
        public long nanoseconds;
    }

    // =========================================================================
    // EventEngineJfrEvent — EX-EVENT-6005 / EX-EVENT-6006 / EX-EVENT-6007
    // =========================================================================

    @Label("Event Engine Failure")
    @Category({"Exeris", "Telemetry", "Events"})
    @StackTrace(false)
    public static final class EventEngineJfrEvent extends Event {
        @Label("Error Code")    public String errorCode;
        @Label("Engine Name")   public String engineName;
        @Label("Raw Arg 0")     public long   rawArg0;
        @Label("Raw Arg 1")     public long   rawArg1;
    }
}
