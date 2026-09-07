/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.core.bootstrap.health;

import eu.exeris.kernel.core.bootstrap.jfr.BootstrapJfrEvents;
import eu.exeris.kernel.spi.bootstrap.HealthProbe;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Core bootstrap health registry for K8s-like readiness/liveness probes.
 *
 * <p>Implements the read-only {@link HealthProbe} SPI contract so that HTTP
 * handlers and out-of-band reporters can consume probe state without coupling
 * to this Core class.
 *
 * <p>This class intentionally exposes state only through immutable snapshots.
 * It can be queried concurrently from probe threads while bootstrap transitions
 * subsystem states.
 */
public final class KernelHealthMonitor implements HealthProbe {

    private static final String STATUS_STARTING = "STARTING";
    private static final String STATUS_READY = "READY";
    private static final String STATUS_FAILED = "FAILED";
    private static final String STATUS_DEGRADED = "DEGRADED";
    private static final String STATUS_DOWN = "DOWN";
    private static final String STATUS_UP = "UP";

    private final Map<String, SubsystemHealth> subsystems = new ConcurrentHashMap<>();
    private final AtomicBoolean kernelInitialized = new AtomicBoolean(false);
    private final AtomicBoolean kernelStarted = new AtomicBoolean(false);
    private final AtomicBoolean kernelShuttingDown = new AtomicBoolean(false);
    private final AtomicBoolean kernelFailed = new AtomicBoolean(false);

    /**
     * Creates a monitor with no subsystem registered and every kernel flag false.
     *
     * <p>A kernel that has not reported anything is therefore neither ready nor live, which is the
     * answer a probe should get before bootstrap has run.
     */
    public KernelHealthMonitor() {
        // Declared, not added: the implicit no-arg constructor, written out so it can carry a comment.
        super();
    }

    /** Resets monitor state for a fresh bootstrap lifecycle. */
    public void reset() {
        subsystems.clear();
        kernelInitialized.set(false);
        kernelStarted.set(false);
        kernelShuttingDown.set(false);
        kernelFailed.set(false);
    }

    /**
     * Registers a subsystem tracked by this monitor, at state {@link SubsystemState#REGISTERED}.
     * A name already registered is left untouched.
     *
     * @param name                 subsystem name
     * @param requiredForReadiness whether this subsystem must be {@link SubsystemState#RUNNING}
     *                             for {@link #readiness()} to report ready
     */
    public void registerSubsystem(String name, boolean requiredForReadiness) {
        Objects.requireNonNull(name, "name");
        subsystems.putIfAbsent(name, new SubsystemHealth(requiredForReadiness, SubsystemState.REGISTERED));
    }

    /**
     * Latches a kernel-level lifecycle flag for {@code state}: {@code INITIALIZED} is read by
     * {@link #liveness()}; {@code STARTED} and {@code SHUTTING_DOWN} are read by
     * {@link #readiness()}; {@code FAILED} is read by both. A flag is cleared only by
     * {@link #reset()}.
     *
     * @param state the kernel lifecycle transition to record
     */
    public void markKernelState(KernelState state) {
        Objects.requireNonNull(state, "state");
        switch (state) {
            case INITIALIZED -> kernelInitialized.set(true);
            case STARTED -> kernelStarted.set(true);
            case SHUTTING_DOWN -> kernelShuttingDown.set(true);
            case FAILED -> kernelFailed.set(true);
        }
    }

    /**
     * Records a subsystem's lifecycle state transition. Silently does nothing if {@code name}
     * was never registered via {@link #registerSubsystem(String, boolean)}. A transition into or
     * out of {@link SubsystemState#DEGRADED} also emits a JFR health-transition event, since a
     * post-boot flip has no dedicated boot-lifecycle event of its own.
     *
     * @param name     subsystem name
     * @param newState the state to record
     * @see <a href="../../../../../../../docs/adr/ADR-005-jfr-first-telemetry-strategy.md">ADR-005</a>
     */
    public void markSubsystemState(String name, SubsystemState newState) {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(newState, "newState");
        SubsystemHealth[] previousHolder = new SubsystemHealth[1];
        subsystems.computeIfPresent(name, (ignored, previous) -> {
            previousHolder[0] = previous;
            return new SubsystemHealth(previous.requiredForReadiness(), newState);
        });
        SubsystemHealth previous = previousHolder[0];
        // JFR-first (ADR-005): a post-boot RUNNING<->DEGRADED flip has no boot lifecycle event, so
        // emit one here so a flapping dependency leaves an SRE trail. Boot transitions don't involve
        // DEGRADED and keep their own SubsystemInitialized/Started/Stopped events.
        if (previous != null && previous.state() != newState
                && (previous.state() == SubsystemState.DEGRADED || newState == SubsystemState.DEGRADED)) {
            BootstrapJfrEvents.emitHealthTransition(name, previous.state().name(), newState.name());
        }
    }

    /**
     * Looks up the current tracked state of a subsystem.
     *
     * @param name subsystem name
     * @return the subsystem's current state, or {@code null} if {@code name} was never
     *         registered via {@link #registerSubsystem(String, boolean)}
     */
    public SubsystemState stateOf(String name) {
        Objects.requireNonNull(name, "name");
        SubsystemHealth health = subsystems.get(name);
        return health == null ? null : health.state();
    }

    /**
     * Readiness: READY only when the kernel is started and every required subsystem is RUNNING.
     * A required subsystem in {@link SubsystemState#DEGRADED} (a live-but-impaired dependency, e.g.
     * its broker died after boot) drops readiness with a distinct {@code "DEGRADED"} status so the
     * load balancer drains this instance; a still-coming-up required subsystem outranks it for the
     * status label ({@code "STARTING"}). An OPTIONAL subsystem degraded never sheds readiness.
     */
    @Override
    public ProbeSnapshot readiness() {
        if (kernelFailed.get()) {
            return new ProbeSnapshot(STATUS_FAILED, false);
        }
        if (!kernelStarted.get() || kernelShuttingDown.get()) {
            return new ProbeSnapshot(STATUS_STARTING, false);
        }
        return requiredSubsystemReadiness();
    }

    private ProbeSnapshot requiredSubsystemReadiness() {
        boolean anyStarting = false;
        boolean anyDegraded = false;
        for (SubsystemHealth health : subsystems.values()) {
            if (!health.requiredForReadiness() || health.state() == SubsystemState.RUNNING) {
                continue;
            }
            // Any required non-RUNNING, non-DEGRADED state (still INITIALIZED/REGISTERED, or a
            // post-boot FAILED that has not yet escalated to kernel-FAILED — checked before this
            // method) counts as "starting": readiness is down either way, and the label favours
            // "coming up" over "degraded" when both reasons coexist.
            anyDegraded |= health.state() == SubsystemState.DEGRADED;
            anyStarting |= health.state() != SubsystemState.DEGRADED;
        }
        if (anyStarting) {
            return new ProbeSnapshot(STATUS_STARTING, false);
        }
        return anyDegraded
                ? new ProbeSnapshot(STATUS_DEGRADED, false)
                : new ProbeSnapshot(STATUS_READY, true);
    }

    /** Liveness: UP after kernel init, DOWN only when kernel entered failed state. */
    @Override
    public ProbeSnapshot liveness() {
        if (kernelFailed.get()) {
            return new ProbeSnapshot(STATUS_DOWN, false);
        }
        if (kernelInitialized.get()) {
            return new ProbeSnapshot(STATUS_UP, true);
        }
        return new ProbeSnapshot(STATUS_STARTING, false);
    }

    /** Kernel-level lifecycle transitions tracked by {@link #markKernelState(KernelState)}. */
    public enum KernelState {
        /** The kernel's subsystem initialize phase has completed. */
        INITIALIZED,
        /** The kernel's subsystem start phase has completed. */
        STARTED,
        /**
         * Shutdown has begun; {@link #readiness()} reports {@code STARTING} while this holds,
         * unless the kernel has also failed.
         */
        SHUTTING_DOWN,
        /** A mandatory subsystem failed; {@link #readiness()} and {@link #liveness()} both report down. */
        FAILED
    }

    private record SubsystemHealth(boolean requiredForReadiness, SubsystemState state) {
    }

    /** Per-subsystem lifecycle states tracked by {@link #markSubsystemState(String, SubsystemState)}. */
    public enum SubsystemState {
        /** Known to this monitor via {@link #registerSubsystem(String, boolean)}, not yet initialized. */
        REGISTERED,
        /** The subsystem's {@code initialize()} has completed. */
        INITIALIZED,
        /** The subsystem's {@code start()} has completed and it is currently running. */
        RUNNING,
        /** Live but impaired (e.g. a dependency failed after boot); reversible back to RUNNING. */
        DEGRADED,
        /** The subsystem failed during {@code initialize()} or {@code start()}. */
        FAILED,
        /** The subsystem's {@code stop()} has completed. */
        STOPPED
    }
}


