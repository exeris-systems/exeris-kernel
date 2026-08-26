/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.spi.scheduling;

/**
 * Observable lifecycle state of a submitted job (ADR-057 §6).
 *
 * <p>A repeating job stays {@link #SCHEDULED} between runs and shows {@link #RUNNING} only while a
 * dispatch is in flight; it reaches a terminal state only through cancellation or scheduler shutdown.
 *
 * @since 0.11.0
 */
public enum JobState {

    /** Registered and waiting for its next fire time. */
    SCHEDULED,

    /** A dispatch is currently executing. */
    RUNNING,

    /** A one-shot job ran to completion, or a repeating job was drained at shutdown. */
    COMPLETED,

    /** Cancelled through {@link JobHandle#cancel()} before or between runs. */
    CANCELLED,

    /**
     * The last dispatch ended in failure, including the fail-closed refusal of a job that carried no
     * captured identity context (ADR-057 §5).
     */
    FAILED
}
