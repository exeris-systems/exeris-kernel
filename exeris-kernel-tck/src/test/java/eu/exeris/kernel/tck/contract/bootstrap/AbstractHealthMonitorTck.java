/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.tck.contract.bootstrap;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * TCK contract for readiness/liveness health monitor semantics.
 */
public abstract class AbstractHealthMonitorTck {

    protected abstract HealthMonitorAdapter createMonitor();

    protected interface HealthMonitorAdapter {
        void register(String subsystem, boolean required);

        void markKernelInitialized();

        void markKernelStarted();

        void markKernelFailed();

        void markSubsystemRunning(String subsystem);

        void markSubsystemFailed(String subsystem);

        boolean readinessUp();

        boolean livenessUp();
    }

    @Test
    @DisplayName("readiness is DOWN before kernel start")
    void readinessDownBeforeKernelStart() {
        HealthMonitorAdapter monitor = createMonitor();
        monitor.register("memory", true);
        monitor.markSubsystemRunning("memory");
        monitor.markKernelInitialized();

        assertThat(monitor.readinessUp()).isFalse();
    }

    @Test
    @DisplayName("readiness is UP when all required subsystems are RUNNING and kernel started")
    void readinessUpWhenRequiredRunning() {
        HealthMonitorAdapter monitor = createMonitor();
        monitor.register("memory", true);
        monitor.register("events", false);
        monitor.markSubsystemRunning("memory");
        monitor.markKernelInitialized();
        monitor.markKernelStarted();

        assertThat(monitor.readinessUp()).isTrue();
    }

    @Test
    @DisplayName("required subsystem failure moves readiness to DOWN")
    void requiredFailureDropsReadiness() {
        HealthMonitorAdapter monitor = createMonitor();
        monitor.register("memory", true);
        monitor.markSubsystemRunning("memory");
        monitor.markKernelInitialized();
        monitor.markKernelStarted();
        monitor.markSubsystemFailed("memory");

        assertThat(monitor.readinessUp()).isFalse();
    }

    @Test
    @DisplayName("optional subsystem failure does not drop readiness")
    void optionalFailureDoesNotDropReadiness() {
        HealthMonitorAdapter monitor = createMonitor();
        monitor.register("memory", true);
        monitor.register("events", false);
        monitor.markSubsystemRunning("memory");
        monitor.markSubsystemFailed("events");
        monitor.markKernelInitialized();
        monitor.markKernelStarted();

        assertThat(monitor.readinessUp()).isTrue();
    }

    @Test
    @DisplayName("kernel failed state moves liveness to DOWN")
    void failedKernelDropsLiveness() {
        HealthMonitorAdapter monitor = createMonitor();
        monitor.markKernelInitialized();
        monitor.markKernelFailed();

        assertThat(monitor.livenessUp()).isFalse();
    }
}

