/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */

/**
 * Read-only kernel <em>state</em> introspection SPI (ADR-033).
 *
 * <p>{@link eu.exeris.kernel.spi.diagnostics.KernelDiagnostics} is the single public contract for
 * reading kernel composition out-of-process; {@link eu.exeris.kernel.spi.diagnostics.KernelDiagnosticsProvider}
 * is its {@link java.util.ServiceLoader}-discovered factory. The {@code *Snapshot} records are the
 * immutable value records serving as JSON wire carriers (each leads with {@code schemaVersion}).
 *
 * <h2>Boundary (The Wall, ADR-006 / ADR-039)</h2>
 * <p>This package is implementation-blind and event-free: it MUST NOT depend on
 * {@code exeris-kernel-core}, any host-runtime type, {@code eu.exeris.telemetry.spec.*}, JFR
 * {@code @Event} types, or any frame / {@code rawArgs} type. Live <em>events</em> stay on the JFR /
 * Glass-Box side; this SPI carries <em>state</em> only. The separation is enforced structurally by an
 * ArchUnit rule in {@code exeris-kernel-tck}.
 *
 * @since 0.9.0
 */
package eu.exeris.kernel.spi.diagnostics;
