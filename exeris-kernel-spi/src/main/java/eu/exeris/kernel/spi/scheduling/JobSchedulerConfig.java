/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.spi.scheduling;

import java.util.Objects;

/**
 * Scheduler configuration (ADR-057 §1).
 *
 * <p>Deliberately small. Thread-pool sizing has no place here — jobs dispatch on virtual threads, so
 * there is no pool to size, and a knob that did nothing would be worse than an absent one.
 *
 * @param schedulerName name used in JFR events and diagnostics
 * @since 0.11
 */
public record JobSchedulerConfig(String schedulerName) {

    /** Name used when a caller does not care to pick one. */
    public static final String DEFAULT_NAME = "default";

    /**
     * Canonical constructor.
     *
     * @throws NullPointerException     if {@code schedulerName} is {@code null}
     * @throws IllegalArgumentException if {@code schedulerName} is blank
     */
    public JobSchedulerConfig {
        Objects.requireNonNull(schedulerName, "schedulerName must not be null");
        if (schedulerName.isBlank()) {
            throw new IllegalArgumentException("schedulerName must not be blank");
        }
    }

    /**
     * Configuration under {@link #DEFAULT_NAME}.
     *
     * @return the default configuration
     */
    public static JobSchedulerConfig defaults() {
        return new JobSchedulerConfig(DEFAULT_NAME);
    }
}
