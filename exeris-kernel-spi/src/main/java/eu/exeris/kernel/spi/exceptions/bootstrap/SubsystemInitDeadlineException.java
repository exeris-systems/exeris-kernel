/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.spi.exceptions.bootstrap;

import eu.exeris.kernel.spi.exceptions.ExerisKernelException;
import eu.exeris.kernel.spi.exceptions.KernelErrorCodes;

/**
 * Thrown when a subsystem's {@code initialize()} does not return within the initialization deadline
 * the kernel was built with.
 *
 * <p>The deadline holds whether {@code initialize()} then returns normally, throws, or ignores the
 * interrupt the orchestrator delivered at the deadline. When it threw, that throwable is the
 * {@linkplain #getCause() cause}; when it returned, there is no cause.
 *
 * <h2>rawArgs Binary Layout (Enterprise Glass-Box)</h2>
 * <pre>
 * index 0 → String  subsystemName   (the subsystem whose initialize() ran past the deadline)
 * index 1 → long    deadlineMs      (the configured deadline, in milliseconds)
 * </pre>
 *
 * <h2>Error Code</h2>
 * <p>{@value KernelErrorCodes#EX_BOOT_0003}
 *
 * @apiNote Only {@code initialize()} is bounded. {@code start()} and {@code stop()} are not.
 * @since 0.13
 */
public final class SubsystemInitDeadlineException extends ExerisKernelException {

    /** Static message template stored as a JVM constant — never re-allocated. */
    private static final String MESSAGE = "Subsystem initialization deadline exceeded";

    /**
     * Records a missed initialization deadline.
     *
     * @param subsystemName logical name of the subsystem whose {@code initialize()} ran past the deadline
     * @param deadlineMs    the configured deadline, in milliseconds
     * @param cause         the throwable {@code initialize()} threw, or {@code null} when it returned
     */
    public SubsystemInitDeadlineException(String subsystemName, long deadlineMs, Throwable cause) {
        super(KernelErrorCodes.EX_BOOT_0003, MESSAGE, cause, subsystemName, deadlineMs);
    }

    /**
     * Returns the logical name of the subsystem whose initialization missed the deadline.
     *
     * <p>Convenience accessor over {@code (String) rawArgs()[0]}.
     *
     * @return subsystem name; never {@code null}
     */
    public String subsystemName() {
        return (String) rawArgs()[0];
    }

    /**
     * Returns the configured initialization deadline.
     *
     * <p>Convenience accessor over {@code (Long) rawArgs()[1]}.
     *
     * @return the deadline, in milliseconds
     */
    public long deadlineMs() {
        return (Long) rawArgs()[1];
    }
}
