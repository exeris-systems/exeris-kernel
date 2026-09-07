/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
/**
 * Job scheduling SPI (ADR-057) — deferred and repeating work, tenant-scoped by capture.
 *
 * <p>The contract covers <em>when</em> work runs and <em>whose</em> identity it runs under. It does
 * not cover durable job stores, leader election, or distributed coordination: those need the
 * coordination seam, which is a separate decision, and naming them here would invite a driver to
 * invent a parallel mechanism.
 *
 * @since 0.11
 */
package eu.exeris.kernel.spi.scheduling;
