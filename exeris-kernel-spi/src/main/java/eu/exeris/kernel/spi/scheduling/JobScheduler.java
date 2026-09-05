/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.spi.scheduling;

/**
 * SPI: schedules work to run later, once or repeatedly (ADR-057).
 *
 * <h2>Identity crosses the boundary by capture</h2>
 * <p>{@link #submit(JobDescriptor)} captures the ambient {@code PrincipalContext} and
 * {@code StorageContext} and rebinds them on the dispatching thread. A job submitted with neither
 * bound fails closed at dispatch: it does not run under an ambient or default identity (ADR-057 §5).
 * The capture is a <em>snapshot</em> — a job firing an hour later carries the authority that
 * scheduled it, which may since have been revoked.
 *
 * <h2>Lifecycle boundary</h2>
 * <p>Jobs dispatch on virtual threads rather than inside a structured scope, so containment comes
 * from {@link JobHandle#cancel()} plus the drain in {@link #close()} (ADR-057 §6). Together they are
 * the structured lifecycle boundary; a driver must not dispatch work that no handle can reach.
 *
 * <h2>Timing</h2>
 * <p>Drivers own their timing loop against an injected time source rather than delegating to a
 * scheduled executor, which is what makes trigger behaviour testable without sleeping (ADR-057 §3-4).
 *
 * @since 0.11
 */
public interface JobScheduler extends AutoCloseable {

    /**
     * Registers a job and starts its schedule.
     *
     * @param descriptor what to run and when
     * @return a handle to the registered job
     * @throws NullPointerException if {@code descriptor} is {@code null}
     * @throws eu.exeris.kernel.spi.exceptions.scheduling.JobSchedulerException if the scheduler is
     *         already closed
     */
    JobHandle submit(JobDescriptor descriptor);

    /**
     * Looks up a previously submitted job.
     *
     * @param jobId the id from {@link JobHandle#jobId()}
     * @return the handle, or {@code null} if no job with that id is known to this scheduler
     */
    JobHandle handle(String jobId);

    /**
     * Stops the scheduler and drains outstanding work.
     *
     * <p>No job fires after this returns. Runs already in flight are awaited rather than abandoned —
     * abandoning them would leave the lifetime containment of ADR-057 §6 unenforced at exactly the
     * moment it matters. Idempotent.
     */
    @Override
    void close();
}
