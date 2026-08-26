/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
/**
 * Exeris Kernel SPI — Bootstrap and Subsystem lifecycle contracts (L0).
 *
 * <h2>The Wall</h2>
 * <p>This package is implementation-blind. It contains only pure contracts:
 * <ul>
 *   <li>{@link eu.exeris.kernel.spi.bootstrap.Subsystem} — the lifecycle contract
 *       every kernel subsystem must implement.</li>
 *   <li>{@link eu.exeris.kernel.spi.bootstrap.SubsystemProvider} — the
 *       {@code ServiceLoader} discovery point for subsystem sets.</li>
 *   <li>{@link eu.exeris.kernel.spi.bootstrap.BootstrapSelector} — immutable
 *       Valhalla-ready record expressing which subsystems to activate.</li>
 *   <li>{@link eu.exeris.kernel.spi.bootstrap.BootstrapPhase} — ordered phases
 *       ({@code FOUNDATION → SERVICES → RUNTIME}).</li>
 *   <li>{@link eu.exeris.kernel.spi.exceptions.SubsystemException} — lifecycle
 *       failure wrapper (unchecked, {@code ExerisKernelException}-based,
 *       bound to {@code EX-BOOT-0002}).</li>
 *   <li>{@link eu.exeris.kernel.spi.exceptions.bootstrap.SubsystemCircularDependencyException}
 *       — fatal, pre-allocated L0 error for cycle detection in Kahn's algorithm.</li>
 * </ul>
 *
 * <h2>No compile-time dependencies on drivers/frameworks</h2>
 * <p>This SPI package defines pure contracts only. It has no compile-time dependencies
 *    on specific stacks such as io_uring, Netty, OpenSSL, JDBC, HikariCP, Spring, CDI,
 *    or any other concrete driver or framework.
 *
 * @see eu.exeris.kernel.spi.bootstrap.Subsystem
 * @see eu.exeris.kernel.spi.bootstrap.SubsystemProvider
 * @since 0.5.0
 */
package eu.exeris.kernel.spi.bootstrap;

