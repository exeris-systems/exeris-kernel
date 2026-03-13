/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.core.bootstrap.health;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Core bootstrap health registry for K8s-like readiness/liveness probes.
 *
 * <p>This class intentionally exposes state only through immutable snapshots.
 * It can be queried concurrently from probe threads while bootstrap transitions
 * subsystem states.
 */
public final class KernelHealthMonitor {

    private static final String STATUS_STARTING = "STARTING";
    private static final String STATUS_READY = "READY";
    private static final String STATUS_FAILED = "FAILED";
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

    /** Readiness: UP only when kernel is started and every required subsystem is RUNNING. */
    public ProbeSnapshot readiness() {
        if (kernelFailed.get()) {
            return new ProbeSnapshot(STATUS_FAILED, false);
        }
        if (!kernelStarted.get() || kernelShuttingDown.get()) {
            return new ProbeSnapshot(STATUS_STARTING, false);
        }
        boolean allRequiredRunning = subsystems.values().stream()
                .filter(SubsystemHealth::requiredForReadiness)
                .allMatch(health -> health.state() == SubsystemState.RUNNING);
        return allRequiredRunning
                ? new ProbeSnapshot(STATUS_READY, true)
                : new ProbeSnapshot(STATUS_STARTING, false);
    }

    /** Liveness: UP after kernel init, DOWN only when kernel entered failed state. */
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

    public record ProbeSnapshot(String status, boolean healthy) {
    }

    private record SubsystemHealth(boolean requiredForReadiness, SubsystemState state) {
    }

    public enum SubsystemState {
        REGISTERED,
        INITIALIZED,
        RUNNING,
        FAILED,
        STOPPED
    }
}


