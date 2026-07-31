/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.spi.scheduling;

import java.util.Objects;

/**
 * What to run, and when (ADR-057 §1).
 *
 * <p>Carries no identity. The principal and storage context are captured by the scheduler at
 * submission and rebound at dispatch (ADR-057 §5), so a descriptor cannot be built holding one
 * tenant's context and submitted under another's.
 *
 * @param jobName human-readable name, used in JFR events and diagnostics
 * @param trigger when the job fires
 * @param body    the work; may block, and runs on a virtual thread
 * @since 0.11.0
 */
public record JobDescriptor(String jobName, JobTrigger trigger, Runnable body) {

    /**
     * Canonical constructor.
     *
     * @throws NullPointerException     if any component is {@code null}
     * @throws IllegalArgumentException if {@code jobName} is blank
     */
    public JobDescriptor {
        Objects.requireNonNull(jobName, "jobName must not be null");
        Objects.requireNonNull(trigger, "trigger must not be null");
        Objects.requireNonNull(body, "body must not be null");
        if (jobName.isBlank()) {
            throw new IllegalArgumentException("jobName must not be blank");
        }
    }
}
