/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.spi.scheduling;

/**
 * A submitted job, addressable after the fact (ADR-057 §6).
 *
 * <p>The handle is half of the structured lifecycle boundary this SPI substitutes for
 * {@code StructuredTaskScope}: every dispatched job is reachable from one, and scheduler shutdown
 * drains or cancels everything still reachable. A dispatch that no handle can name would break that
 * property, so a driver must not create one.
 *
 * @since 0.11
 */
public interface JobHandle {

    /**
     * Stable identifier assigned at submission, unique within the scheduler.
     *
     * @return the job id; never {@code null}
     */
    String jobId();

    /**
     * The name the job was submitted under, for diagnostics and JFR correlation.
     *
     * @return the job name; never {@code null}
     */
    String jobName();

    /**
     * Current lifecycle state.
     *
     * @return the state; never {@code null}
     */
    JobState state();

    /**
     * Cancels the job: it will not fire again.
     *
     * <p>Idempotent. A run already in flight is <em>not</em> interrupted — the contract promises no
     * further dispatches, not that a running job stops mid-work, because interrupting arbitrary
     * application code at an arbitrary point is not something a scheduler can do safely.
     *
     * @return {@code true} if this call moved the job to {@link JobState#CANCELLED}, {@code false} if
     *         it was already cancelled or had already reached a terminal state
     */
    boolean cancel();
}
