/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.core.telemetry.jfr;

import jdk.jfr.Category;
import jdk.jfr.Description;
import jdk.jfr.Event;
import jdk.jfr.Label;
import jdk.jfr.Name;
import jdk.jfr.StackTrace;

/**
 * Container for the Core module's strongly-typed {@code jdk.jfr.Event} subclasses.
 *
 * <p>Each nested class corresponds to one lifecycle transition or metric kind emitted by
 * {@link eu.exeris.kernel.core.telemetry.JfrTelemetrySink} — this class holds no behaviour of its
 * own beyond grouping them under one JFR event-category namespace and is never instantiated.
 */
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
    /**
     * Creates an unrecorded event.
     *
     * <p>The emitter assigns the public fields and calls {@link Event#commit()}. An instance that is never
     * committed contributes nothing to a recording.
     */
    public KernelLifecycleJfrEvent() {
        // Declared, not added: the implicit no-arg constructor, written out so it can carry a comment.
        super();
    }

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
        /**
         * Either {@code EX-NET-4001} (bind failure) or {@code EX-NET-4005} (start failure).
         */
        @Label("Error Code")
        public String errorCode;

        /**
         * Name of the transport engine that failed to bind or start.
         */
        @Label("Transport Name")
        public String transportName;

        /**
         * Port number; {@code -1} if not applicable.
         */
        @Label("Port")
        public int port;

        /**
         * Kernel component that emitted the event (compile-time constant on call site).
         */
        @Label("Component")
        public String component;
    /**
     * Creates an unrecorded event.
     *
     * <p>The emitter assigns the public fields and calls {@link Event#commit()}. An instance that is never
     * committed contributes nothing to a recording.
     */
    public TransportBindJfrEvent() {
        // Declared, not added: the implicit no-arg constructor, written out so it can carry a comment.
        super();
    }

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
     * <p>rawArgs layout: index 0 = {@code long blockTimeMs}, index 1 = {@code String carrierThreadName}.
     */
    @Name("eu.exeris.kernel.telemetry.CarrierPinned")
    @Label("Carrier Thread Pinned")
    @Description("Emitted when a virtual-thread carrier is blocked beyond the kill threshold — EX-RUN-3002")
    @Category({"Exeris Kernel", "Runtime"})
    @StackTrace(false)
    public static final class CarrierPinnedJfrEvent extends Event {
        /**
         * Always {@code EX-RUN-3002}, the sole error code this event reports.
         */
        @Label("Error Code")
        public String errorCode;

        /**
         * Duration in milliseconds the carrier thread was blocked by the pinning virtual thread.
         */
        @Label("Block Time (ms)")
        public long blockTimeMs;

        /**
         * Name of the pinned carrier thread.
         */
        @Label("Carrier Thread Name")
        public String carrierThreadName;

        /**
         * Kernel component that emitted the event (compile-time constant on call site).
         */
        @Label("Component")
        public String component;
    /**
     * Creates an unrecorded event.
     *
     * <p>The emitter assigns the public fields and calls {@link Event#commit()}. An instance that is never
     * committed contributes nothing to a recording.
     */
    public CarrierPinnedJfrEvent() {
        // Declared, not added: the implicit no-arg constructor, written out so it can carry a comment.
        super();
    }

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
        /**
         * Always {@code EX-MEM-1001}, the sole error code this event reports.
         */
        @Label("Error Code")
        public String errorCode;

        /**
         * Number of bytes the failed allocation requested.
         */
        @Label("Requested Bytes")
        public long requestedBytes;

        /**
         * Number of bytes available in the allocator at the time of failure.
         */
        @Label("Available Bytes")
        public long availableBytes;

        /**
         * Kernel component that emitted the event (compile-time constant on call site).
         */
        @Label("Component")
        public String component;
    /**
     * Creates an unrecorded event.
     *
     * <p>The emitter assigns the public fields and calls {@link Event#commit()}. An instance that is never
     * committed contributes nothing to a recording.
     */
    public MemoryExhaustionJfrEvent() {
        // Declared, not added: the implicit no-arg constructor, written out so it can carry a comment.
        super();
    }

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
        /**
         * Name of the counter or gauge this event records.
         */
        @Label("Metric Name")
        public String metricName;

        /**
         * {@code "COUNTER"} or {@code "GAUGE"}.
         */
        @Label("Type")
        public String metricType;

        /**
         * Recorded value — the increment delta for a counter, or the absolute value for a gauge.
         */
        @Label("Value")
        public long value;
    /**
     * Creates an unrecorded event.
     *
     * <p>The emitter assigns the public fields and calls {@link Event#commit()}. An instance that is never
     * committed contributes nothing to a recording.
     */
    public KernelMetricJfrEvent() {
        // Declared, not added: the implicit no-arg constructor, written out so it can carry a comment.
        super();
    }

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
        /**
         * Name of the latency metric this event records.
         */
        @Label("Metric Name")
        public String metricName;

        /**
         * Latency sample in nanoseconds.
         */
        @Label("Duration (ns)")
        public long nanoseconds;
    /**
     * Creates an unrecorded event.
     *
     * <p>The emitter assigns the public fields and calls {@link Event#commit()}. An instance that is never
     * committed contributes nothing to a recording.
     */
    public KernelLatencyJfrEvent() {
        // Declared, not added: the implicit no-arg constructor, written out so it can carry a comment.
        super();
    }

    }
}
