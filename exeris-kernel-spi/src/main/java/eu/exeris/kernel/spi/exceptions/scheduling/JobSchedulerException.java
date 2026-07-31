/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
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
 *   <li>{@value KernelErrorCodes#EX_JOB_9003} — a job body threw</li>
 * </ul>
 *
 * @since 0.11.0
 */
public final class JobSchedulerException extends ExerisKernelException {

    private static final String CLOSED_MSG = "Scheduler is closed";

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

}
