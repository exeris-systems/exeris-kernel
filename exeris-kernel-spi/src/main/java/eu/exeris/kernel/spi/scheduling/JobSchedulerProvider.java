/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.spi.scheduling;

/**
 * SPI: pluggable {@link JobScheduler} factory — the discovery handle the bootstrapper loads through
 * {@link java.util.ServiceLoader} (ADR-057 §1).
 *
 * <h2>Open-Core (The Wall)</h2>
 * <p>The contract is implementation-blind. The Community binding dispatches in-process on virtual
 * threads against an injected time source; a distributed or durable-backend binding would select a
 * leader through the coordination seam rather than inventing a parallel mechanism. Neither appears in
 * any signature here.
 *
 * @since 0.11.0
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
     * Community is {@code 0}, Enterprise {@code 100}.
     *
     * @return priority
     */
    default int priority() {
        return 0;
    }
}
