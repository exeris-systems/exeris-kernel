/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.spi.exceptions;

/**
 * Thrown when a {@code Subsystem} fails during a lifecycle transition
 * ({@link Phase#INITIALIZE}, {@link Phase#START}, or {@link Phase#STOP}).
 *
 * <p>Extends {@link ExerisKernelException} — unchecked, because a bootstrap failure is fatal and
 * a caller cannot meaningfully recover from it at the call site: forcing every layer between the
 * throw site and the top-level {@code KernelBootstrap} to declare {@code throws
 * SubsystemException} would only leak lifecycle semantics up the call stack (The Wall). The
 * {@code StructuredTaskScope} used in the bootstrap sequence catches {@code Throwable} regardless,
 * so checked-versus-unchecked makes no difference there either.
 *
 * {@snippet lang="java" :
 * // Wrong — allocates StringBuilder + String on every throw:
 * super("[%s/%s] %s".formatted(subsystemName, phase, message));
 *
 * // Correct — raw context values passed to rawArgs, zero String formatting on the throw path:
 * super(KernelErrorCodes.EX_BOOT_0002, "Subsystem lifecycle failure", cause,
 *       subsystemName, phase, message);
 * }
 *
 * <h2>rawArgs Binary Layout (Enterprise Glass-Box)</h2>
 * <pre>
 * index 0 → String  subsystemName   (logical name of the failing subsystem)
 * index 1 → Phase   phase           (INITIALIZE | START | STOP)
 * index 2 → String  detailMessage   (static detail string; no formatting permitted)
 * </pre>
 *
 * <h2>Error Code</h2>
 * <p>{@value KernelErrorCodes#EX_BOOT_0002}
 *
 * @since 0.5
 */
public final class SubsystemException extends ExerisKernelException {

    /**
     * Static message template stored as a JVM constant – never re-allocated.
     */
    private static final String MESSAGE = "Subsystem lifecycle failure";

    /**
     * Lifecycle phase in which the failure occurred.
     *
     * <p>Defined as a top-level member of {@code SubsystemException} so that the SPI
     * remains self-contained and callers can reference {@code SubsystemException.Phase}
     * without importing anything outside this package.
     */
    public enum Phase {
        /** Failure occurred inside {@code Subsystem.initialize()}. */
        INITIALIZE,
        /** Failure occurred inside {@code Subsystem.start()}. */
        START,
        /** Failure occurred inside {@code Subsystem.stop()}. */
        STOP
    }

    // -----------------------------------------------------------------------
    // Constructors
    // -----------------------------------------------------------------------

    /**
     * Records a lifecycle failure with no chained cause — the detail string is the whole
     * diagnosis.
     *
     * @param subsystemName logical name of the subsystem that failed (e.g. {@code "MemorySubsystem"})
     * @param phase         lifecycle phase in which the failure occurred
     * @param detail        static, pre-defined detail string – <strong>no runtime formatting permitted</strong>
     */
    public SubsystemException(String subsystemName, Phase phase, String detail) {
        super(KernelErrorCodes.EX_BOOT_0002, MESSAGE, null, subsystemName, phase, detail);
    }

    /**
     * Records a lifecycle failure, keeping the upstream {@link Throwable} from the subsystem as
     * the cause for forensics.
     *
     * @param subsystemName logical name of the subsystem that failed
     * @param phase         lifecycle phase in which the failure occurred
     * @param detail        static, pre-defined detail string
     * @param cause         the upstream throwable that triggered the failure
     */
    public SubsystemException(String subsystemName, Phase phase, String detail, Throwable cause) {
        super(KernelErrorCodes.EX_BOOT_0002, MESSAGE, cause, subsystemName, phase, detail);
    }

    // -----------------------------------------------------------------------
    // Typed accessors – read rawArgs with explicit semantics
    // -----------------------------------------------------------------------

    /**
     * Returns the logical name of the subsystem that failed.
     *
     * <p>Convenience accessor over {@code (String) rawArgs()[0]}.
     *
     * @return subsystem name; never {@code null}
     */
    public String subsystemName() {
        return (String) rawArgs()[0];
    }

    /**
     * Returns the lifecycle phase during which the failure occurred.
     *
     * <p>Convenience accessor over {@code (Phase) rawArgs()[1]}.
     *
     * @return the {@link Phase} enum value; never {@code null}
     */
    public Phase phase() {
        return (Phase) rawArgs()[1];
    }

    /**
     * Returns the static detail message supplied at construction time.
     *
     * <p>Convenience accessor over {@code (String) rawArgs()[2]}.
     *
     * @return detail string; never {@code null}
     */
    public String detail() {
        return (String) rawArgs()[2];
    }
}


