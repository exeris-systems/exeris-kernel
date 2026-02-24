/*
 * Copyright (C) 2025-2026 Exeris. All rights reserved.
 *
 * This code is part of the Exeris Systems.
 * Distributed under the proprietary Exeris Software License.
 * Unauthorized copying or distribution is prohibited.
 */

/**
 * Exeris Kernel SPI – Telemetry subsystem contracts (L0).
 *
 * <h2>Key Types</h2>
 * <ul>
 *   <li>{@link eu.exeris.kernel.spi.telemetry.TelemetryProvider} — {@code ServiceLoader} factory;
 *       creates and owns a {@link eu.exeris.kernel.spi.telemetry.TelemetrySink} from a given
 *       {@link eu.exeris.kernel.spi.telemetry.TelemetryConfig}</li>
 *   <li>{@link eu.exeris.kernel.spi.telemetry.TelemetrySink} — low-frequency event receiver;
 *       the single method {@code emit(KernelEvent)} is the only ingestion point for all
 *       kernel lifecycle diagnostics</li>
 *   <li>{@link eu.exeris.kernel.spi.telemetry.KernelEvent} — immutable event record carrying
 *       a structured code, {@link eu.exeris.kernel.spi.telemetry.EventLevel}, wall-clock
 *       timestamp, optional {@link eu.exeris.kernel.spi.exceptions.ExerisKernelException},
 *       and a component label; candidate for {@code value record} once JEP 401 is mainline</li>
 *   <li>{@link eu.exeris.kernel.spi.telemetry.EventLevel} — severity enum:
 *       {@code INFO}, {@code WARN}, {@code ERROR}</li>
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
 *   <li><strong>Community</strong> — console sink (structured text) and JFR sink
 *       (custom {@code jdk.jfr.Event} subclasses); low-overhead, heap-only</li>
 *   <li><strong>Enterprise</strong> — {@code BinaryBlackBoxSink}: reads
 *       {@link eu.exeris.kernel.spi.exceptions.ExerisKernelException#rawArgs()} as a typed
 *       binary struct and writes directly to an off-heap mmap ring buffer — zero GC overhead
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

