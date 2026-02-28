/*
 * Copyright (C) 2025-2026 Exeris. All rights reserved.
 *
 * This code is part of the Exeris Systems.
 * Distributed under the proprietary Exeris Software License.
 * Unauthorized copying or distribution is prohibited.
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
 *   <li>{@link eu.exeris.kernel.spi.bootstrap.SubsystemException} — lifecycle
 *       failure wrapper.</li>
 *   <li>{@link eu.exeris.kernel.spi.bootstrap.SubsystemCircularDependencyException}
 *       — fatal, pre-allocated L0 error for cycle detection in Kahn's algorithm.</li>
 * </ul>
 *
 * <h2>No references to</h2>
 * <p>io_uring, Netty, OpenSSL, JDBC, HikariCP, Spring, CDI, or any concrete driver.
 *
 * @see eu.exeris.kernel.spi.bootstrap.Subsystem
 * @see eu.exeris.kernel.spi.bootstrap.SubsystemProvider
 * @since 0.5.0
 */
package eu.exeris.kernel.spi.bootstrap;

