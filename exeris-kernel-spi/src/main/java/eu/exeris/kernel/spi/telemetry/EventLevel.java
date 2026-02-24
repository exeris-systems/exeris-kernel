/*
 * Copyright (C) 2025-2026 Exeris. All rights reserved.
 *
 * This code is part of the Exeris Systems.
 * Distributed under the proprietary Exeris Software License.
 * Unauthorized copying or distribution is prohibited.
 */
package eu.exeris.kernel.spi.telemetry;

/**
 * SPI: Event severity level for {@link KernelEvent}.
 *
 * @since 0.5.0
 */
public enum EventLevel {
    /** Low-frequency diagnostic info (bootstrap, config). */
    INFO,
    /** Soft degradation — kernel still operational but approaching a limit. */
    WARN,
    /** Hard failure — subsystem may be unavailable. */
    ERROR,
    /** Unrecoverable failure — kernel shutdown imminent. */
    FATAL
}

