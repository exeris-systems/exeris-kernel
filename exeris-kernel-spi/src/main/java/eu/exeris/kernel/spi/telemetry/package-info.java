/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
/**
 * Exeris Kernel SPI – Telemetry subsystem contracts (L0).
 *
 * <h2>Key Types</h2>
 * <ul>
 *   <li>{@link eu.exeris.kernel.spi.telemetry.TelemetryProvider} — {@code ServiceLoader} factory;
 *       creates and owns a {@link eu.exeris.kernel.spi.telemetry.TelemetrySink} from a given
 *       {@link eu.exeris.kernel.spi.telemetry.TelemetryConfig}</li>
 *   <li>{@link eu.exeris.kernel.spi.telemetry.TelemetrySink} — unified ingestion contract
 *       exposing four methods: {@code emit(KernelEvent)} for structured lifecycle events,
 *       {@code increment(String, long)} for counters, {@code gauge(String, long)} for
 *       absolute metric values, and {@code latency(String, long)} for nanosecond-resolution
 *       timing samples</li>
 *   <li>{@link eu.exeris.kernel.spi.telemetry.KernelEvent} — immutable event record carrying
 *       a structured code, {@link eu.exeris.kernel.spi.telemetry.EventLevel}, wall-clock
 *       timestamp, optional {@link eu.exeris.kernel.spi.exceptions.ExerisKernelException},
 *       and a component label; candidate for {@code value record} once JEP 401 is mainline</li>
 *   <li>{@link eu.exeris.kernel.spi.telemetry.EventLevel} — severity enum:
 *       {@code INFO}, {@code WARN}, {@code ERROR}, {@code FATAL}</li>
 *   <li>{@link eu.exeris.kernel.spi.telemetry.TelemetryConfig} — immutable configuration record
 *       controlling which sinks are active and their resource budgets</li>
 * </ul>
 *
 * <h2>The Wall (SPI Compliance)</h2>
 * <p>This package is <strong>implementation-blind</strong>: no references to JFR event classes,
 * off-heap mmap internals, binary serialisation formats, or any community/enterprise driver class.
 * Sink implementations that read {@link eu.exeris.kernel.spi.exceptions.ExerisKernelException#rawArgs()}
 * directly into off-heap buffers MUST reside in the Enterprise module.
 *
 * <h2>Tier Implementations</h2>
 * <ul>
 *   <li><strong>Community</strong> — console sink (structured text) and a Flight Recorder sink
 *       that emits structured kernel events as Flight Recorder events; low-overhead, heap-only</li>
 *   <li><strong>Enterprise</strong> — {@code BinaryGlassBoxSink}: reads
 *       {@link eu.exeris.kernel.spi.exceptions.ExerisKernelException#rawArgs()} as a typed
 *       binary struct and writes directly to an off-heap ring buffer — zero GC overhead
 *       even under saturation</li>
 * </ul>
 *
 * <h2>Zero-Allocation Contract</h2>
 * <p>{@link eu.exeris.kernel.spi.telemetry.TelemetrySink#emit} is a low-frequency path
 * (lifecycle events only — bootstrap, allocation failure, transport bind). Factory methods on
 * {@link eu.exeris.kernel.spi.telemetry.KernelEvent} allocate a record instance and capture
 * a wall-clock timestamp; on truly hot paths the sink implementation should capture the
 * timestamp itself to minimise per-event allocation.
 *
 * @see <a href="../../../../../../docs/subsystems/telemetry.md">telemetry.md</a>
 */
package eu.exeris.kernel.spi.telemetry;
