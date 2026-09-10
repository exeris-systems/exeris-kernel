/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.spi.scheduling;

/**
 * SPI: pluggable {@link JobScheduler} factory — the discovery handle the bootstrapper loads through
 * {@link java.util.ServiceLoader} (ADR-057 §1).
 *
 * <h2>Open-Core (The Wall)</h2>
 * <p>The contract is implementation-blind: neither dispatch strategy nor coordination mechanism
 * appears in any signature here.
 *
 * @implNote The Community binding dispatches in-process on virtual threads against an injected time
 *           source; a distributed or durable-backend binding would select a leader through the
 *           coordination seam rather than inventing a parallel mechanism.
 * @since 0.11
 */
public interface JobSchedulerProvider {

    /**
     * Creates a scheduler from the given configuration.
     *
     * @param config scheduler configuration
     * @return a started scheduler
     */
    JobScheduler createScheduler(JobSchedulerConfig config);

    /**
     * Unique identifier for this provider (e.g. {@code "job-loom-community"}).
     *
     * @return stable provider identifier
     */
    String providerId();

    /**
     * Display name used in bootstrap JFR events and diagnostics.
     *
     * @return human-readable provider name; never {@code null}
     */
    String providerName();

    /**
     * Selection priority when several providers are on the classpath; higher wins.
     *
     * @return priority
     * @implNote Community uses {@code 0}, Enterprise {@code 100}.
     */
    default int priority() {
        return 0;
    }
}
