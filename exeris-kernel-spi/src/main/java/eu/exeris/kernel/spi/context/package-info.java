/*
 * Copyright (C) 2025-2026 Exeris. All rights reserved.
 */

/**
 * Exeris Kernel SPI – Context propagation via {@link java.lang.ScopedValue} (JEP 506).
 *
 * <p>Contains {@link eu.exeris.kernel.spi.context.KernelProviders} — the single source
 * of truth for all SPI provider slots injected by the kernel bootstrapper.
 *
 * <h2>The Wall</h2>
 * <p>No implementation-specific classes (io_uring, HikariCP, Netty) appear in this package.
 */
package eu.exeris.kernel.spi.context;

