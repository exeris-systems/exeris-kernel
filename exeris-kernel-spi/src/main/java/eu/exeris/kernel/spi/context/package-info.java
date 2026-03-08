/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
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

