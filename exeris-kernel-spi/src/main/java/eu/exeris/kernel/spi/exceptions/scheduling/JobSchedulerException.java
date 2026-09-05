/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.spi.exceptions.scheduling;

import eu.exeris.kernel.spi.exceptions.ExerisKernelException;
import eu.exeris.kernel.spi.exceptions.KernelErrorCodes;

/**
 * Thrown when the scheduling subsystem refuses or cannot complete an operation (ADR-057 §9).
 *
 * <h2>Zero-Allocation Telemetry Contract</h2>
 * <p>All domain context is passed as raw {@code Object[]} args to
 * {@link ExerisKernelException#rawArgs()} — no String formatting on the failure path.
 *
 * <h2>Secret safety</h2>
 * <p>Job names are developer-chosen identifiers and are safe to record. No factory captures a
 * principal id, a tenant key, or anything a job body touched.
 *
 * <h2>Error Codes</h2>
 * <ul>
 *   <li>{@value KernelErrorCodes#EX_JOB_9001} — dispatch refused: no captured identity context</li>
 *   <li>{@value KernelErrorCodes#EX_JOB_9002} — the scheduler is closed</li>
 *   <li>{@value KernelErrorCodes#EX_JOB_9004} — no provider on the classpath at bootstrap</li>
 *   <li>{@value KernelErrorCodes#EX_JOB_9003} — a job body threw</li>
 * </ul>
 *
 * @since 0.11
 */
public final class JobSchedulerException extends ExerisKernelException {

    private static final String CLOSED_MSG = "Scheduler is closed";
    private static final String NO_PROVIDER_MSG = "No JobSchedulerProvider on the classpath";

    private JobSchedulerException(String errorCode, String message, Throwable cause,
                                  Object... rawArgs) {
        super(errorCode, message, cause, rawArgs);
    }


    /**
     * A submission arrived after {@link eu.exeris.kernel.spi.scheduling.JobScheduler#close()}.
     *
     * @param schedulerName scheduler display name
     * @param jobName       the job that could not be registered
     * @return exception with rawArgs: [schedulerName, jobName]
     */
    public static JobSchedulerException schedulerClosed(String schedulerName, String jobName) {
        return new JobSchedulerException(
                KernelErrorCodes.EX_JOB_9002, CLOSED_MSG, null, schedulerName, jobName);
    }


    /**
     * Bootstrap found no {@link eu.exeris.kernel.spi.scheduling.JobSchedulerProvider} to select.
     *
     * <p>Terminal rather than degraded: a kernel that boots with scheduling silently absent would let
     * an application submit jobs that never run, which is worse than refusing to start.
     *
     * @param component the bootstrap component that attempted the selection
     * @return exception with rawArgs: [component]
     */
    public static JobSchedulerException noProvider(String component) {
        return new JobSchedulerException(
                KernelErrorCodes.EX_JOB_9004, NO_PROVIDER_MSG, null, component);
    }
}
