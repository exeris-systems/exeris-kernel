/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
/**
 * Job scheduling SPI (ADR-057) — deferred and repeating work, tenant-scoped by capture.
 *
 * <p>The contract covers <em>when</em> work runs and <em>whose</em> identity it runs under. It does
 * not cover durable job stores, leader election, or distributed coordination: those need the
 * coordination seam, which is a separate decision, and naming them here would invite a driver to
 * invent a parallel mechanism.
 *
 * @since 0.11.0
 */
package eu.exeris.kernel.spi.scheduling;
