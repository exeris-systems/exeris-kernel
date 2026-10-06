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
 * {@code StorageContext} and rebinds them on the dispatching thread. The capture is a
 * <em>snapshot</em> — a job firing an hour later carries the authority that scheduled it, which may
 * since have been revoked.
 *
 * <h2>Lifecycle boundary</h2>
 * <p>Jobs dispatch on virtual threads rather than inside a structured scope, so containment comes
 * from {@link JobHandle#cancel()} plus the drain in {@link #close()} (ADR-057 §6): together they
 * stand in for the guarantee a structured scope would otherwise give, that no work outlives it.
 *
 * <h2>Timing</h2>
 * <p>Drivers own their timing loop against an injected time source rather than delegating to a
 * scheduled executor, which is what makes trigger behaviour testable without sleeping (ADR-057 §3-4).
 *
 * @implSpec A submission captured with neither {@code PrincipalContext} nor {@code StorageContext}
 *           bound must fail closed at dispatch — it must not run under an ambient or default
 *           identity — and a driver must not dispatch work that no {@link JobHandle} can reach, so
 *           that {@link #close()} can drain everything still outstanding.
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
     *         already closed ({@code EX-JOB-9002})
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
     * @implSpec Implementations must guarantee that no job fires after this method returns, must
     *           await runs already in flight rather than abandoning them — abandoning them would
     *           leave the lifetime containment of ADR-057 §6 unenforced at exactly the moment it
     *           matters — and must make this method idempotent.
     */
    @Override
    void close();
}
