/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.core.bootstrap.health;

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

    /** Resets monitor state for a fresh bootstrap lifecycle. */
    public void reset() {
        subsystems.clear();
        kernelInitialized.set(false);
        kernelStarted.set(false);
        kernelShuttingDown.set(false);
        kernelFailed.set(false);
    }

    /** Registers a subsystem tracked by this monitor. */
    public void registerSubsystem(String name, boolean requiredForReadiness) {
        Objects.requireNonNull(name, "name");
        subsystems.putIfAbsent(name, new SubsystemHealth(requiredForReadiness, SubsystemState.REGISTERED));
    }

    public void markKernelState(KernelState state) {
        Objects.requireNonNull(state, "state");
        switch (state) {
            case INITIALIZED -> kernelInitialized.set(true);
            case STARTED -> kernelStarted.set(true);
            case SHUTTING_DOWN -> kernelShuttingDown.set(true);
            case FAILED -> kernelFailed.set(true);
        }
    }

    public void markSubsystemState(String name, SubsystemState newState) {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(newState, "newState");
        subsystems.computeIfPresent(name, (ignored, previous) -> new SubsystemHealth(
                previous.requiredForReadiness(),
                newState
        ));
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

    public enum KernelState {
        INITIALIZED,
        STARTED,
        SHUTTING_DOWN,
        FAILED
    }

    private record SubsystemHealth(boolean requiredForReadiness, SubsystemState state) {
    }

    public enum SubsystemState {
        REGISTERED,
        INITIALIZED,
        RUNNING,
        /** Live but impaired (e.g. a dependency failed after boot); reversible back to RUNNING. */
        DEGRADED,
        FAILED,
        STOPPED
    }
}


