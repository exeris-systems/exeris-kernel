/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
/**
 * Read-only kernel <em>state</em> introspection SPI (ADR-033).
 *
 * <p>{@link eu.exeris.kernel.spi.diagnostics.KernelDiagnostics} is the single public contract for
 * reading kernel composition out-of-process; {@link eu.exeris.kernel.spi.diagnostics.KernelDiagnosticsProvider}
 * is its {@link java.util.ServiceLoader}-discovered factory. The {@code *Snapshot} records are the
 * immutable, Valhalla-ready JSON wire carriers (each leads with {@code schemaVersion}).
 *
 * <h2>Boundary (The Wall, ADR-006 / ADR-039)</h2>
 * <p>This package is implementation-blind and event-free: it MUST NOT depend on
 * {@code exeris-kernel-core}, any host-runtime type, {@code eu.exeris.telemetry.spec.*}, JFR
 * {@code @Event} types, or any frame / {@code rawArgs} type. Live <em>events</em> stay on the JFR /
 * Glass-Box side; this SPI carries <em>state</em> only. The separation is enforced structurally by an
 * ArchUnit rule in {@code exeris-kernel-tck}.
 *
 * @since 0.9
 */
package eu.exeris.kernel.spi.diagnostics;
