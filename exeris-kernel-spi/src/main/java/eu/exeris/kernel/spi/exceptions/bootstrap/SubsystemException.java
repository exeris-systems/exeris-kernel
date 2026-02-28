/*
 * Copyright (C) 2025-2026 Exeris. All rights reserved.
 *
 * This code is part of the Exeris Systems.
 * Distributed under the proprietary Exeris Software License.
 * Unauthorized copying or distribution is prohibited.
 */
package eu.exeris.kernel.spi.exceptions.bootstrap;

import eu.exeris.kernel.spi.bootstrap.Subsystem;

/**
 * Thrown when a {@link Subsystem} fails during {@link Subsystem#initialize()} or
 * {@link Subsystem#start()}.
 *
 * <h2>Error codes</h2>
 * <ul>
 *   <li>{@code EX-BOOT-0001} — Dependency cycle detected → {@link SubsystemCircularDependencyException}</li>
 *   <li>{@code EX-BOOT-0002} — Foundation subsystem failure → fatal exit</li>
 *   <li>{@code EX-BOOT-0003} — Timeout during init → kill or degrade</li>
 *   <li>{@code EX-BOOT-0004} — Shutdown hook interrupted → log as CRITICAL</li>
 * </ul>
 *
 * @since 0.5.0
 */
public class SubsystemException extends Exception {

    /**
     * The lifecycle phase during which this exception was thrown.
     */
    public enum Phase {
        /** Exception thrown inside {@link Subsystem#initialize()}. */
        INITIALIZE,
        /** Exception thrown inside {@link Subsystem#start()}. */
        START,
        /** Exception thrown inside {@link Subsystem#stop()} (logged as WARN, not re-thrown). */
        STOP
    }

    private final String subsystemName;
    private final Phase  phase;

    /**
     * Creates a new {@code SubsystemException}.
     *
     * @param subsystemName name of the failing subsystem (from {@link Subsystem#name()})
     * @param phase         lifecycle phase during which the failure occurred
     * @param message       human-readable detail message
     * @param cause         original cause; may be {@code null}
     */
    public SubsystemException(String subsystemName, Phase phase, String message, Throwable cause) {
        super("[" + subsystemName + "] " + phase + " failed: " + message, cause);
        this.subsystemName = subsystemName;
        this.phase         = phase;
    }

    /**
     * Returns the name of the failing subsystem.
     *
     * @return subsystem name; never {@code null}
     */
    public String subsystemName() {
        return subsystemName;
    }

    /**
     * Returns the lifecycle phase during which the failure occurred.
     *
     * @return phase; never {@code null}
     */
    public Phase phase() {
        return phase;
    }
}

