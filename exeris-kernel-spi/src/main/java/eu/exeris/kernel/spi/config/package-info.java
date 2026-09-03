/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
/**
 * Exeris Kernel SPI — Config subsystem contracts (L0 Foundation).
 *
 * <h2>The Wall</h2>
 * <p>This package is implementation-blind. It contains only:
 * <ul>
 *   <li>{@link eu.exeris.kernel.spi.config.ConfigProvider} — the single SPI entry point,
 *       loaded via {@code ServiceLoader}. Contains all Valhalla-ready Value Record types.</li>
 *   <li>{@link eu.exeris.kernel.spi.config.Dynamic} — annotation for hot-reload fields.</li>
 *   <li>{@link eu.exeris.kernel.spi.config.KernelProfile} — profile enum (DEV/TEST/PROD).</li>
 * </ul>
 *
 * <h2>Precedence (ENV wins)</h2>
 * <pre>
 *   Environment Variables  (priority 3 — highest)
 *   External File          (priority 2 — K8s ConfigMap)
 *   Classpath Defaults     (priority 1 — lowest)
 * </pre>
 *
 * <h2>Valhalla Readiness</h2>
 * <p>All records in this package are annotated {@code @ValueCandidate}.
 * No identity operations ({@code ==}, {@code synchronized}, {@code identityHashCode})
 * are used, enabling clean scalarization via C2 JIT Escape Analysis today and
 * heap-flattening once JEP 401 is mainline.
 *
 * @see eu.exeris.kernel.spi.config.ConfigProvider
 * @since 0.5.0
 */
package eu.exeris.kernel.spi.config;

