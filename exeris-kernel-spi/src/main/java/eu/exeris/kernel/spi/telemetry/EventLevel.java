/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.spi.telemetry;

/**
 * SPI: Event severity level for {@link KernelEvent}.
 *
 * @since 0.5.0
 */
public enum EventLevel {
    /**
     * Low-frequency diagnostic info (bootstrap, config).
     */
    INFO,
    /**
     * Soft degradation — kernel still operational but approaching a limit.
     */
    WARN,
    /**
     * Hard failure — subsystem may be unavailable.
     */
    ERROR,
    /**
     * Unrecoverable failure — kernel shutdown imminent.
     */
    FATAL
}
