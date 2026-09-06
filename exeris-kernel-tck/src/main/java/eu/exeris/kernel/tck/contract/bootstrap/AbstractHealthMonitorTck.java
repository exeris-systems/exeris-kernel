/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.tck.contract.bootstrap;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * TCK: verifies readiness/liveness health-monitor semantics — readiness requires the kernel
 * started and every required subsystem RUNNING; a required subsystem's failure or
 * degradation drops readiness (degradation drops readiness without dropping liveness); an
 * optional subsystem's failure or degradation never drops readiness; and a failed kernel
 * drops liveness.
 */
public abstract class AbstractHealthMonitorTck {

    /**
     * Creates the health-monitor adapter under test.
     *
     * @return a fresh {@link HealthMonitorAdapter} with no subsystems registered
     */
    protected abstract HealthMonitorAdapter createMonitor();

    /**
     * Adapts the health monitor under test to the state-transition shape this TCK drives;
     * bindings implement it over their concrete monitor type.
     */
    protected interface HealthMonitorAdapter {

        /**
         * Registers {@code subsystem} with the monitor, declaring whether it is required
         * for readiness.
         *
         * @param subsystem the subsystem name to track
         * @param required  {@code true} if this subsystem's failure or degradation must
         *                  drop readiness; {@code false} if it must not
         */
        void register(String subsystem, boolean required);

        /** Marks the kernel's initialize phase complete. */
        void markKernelInitialized();

        /** Marks the kernel's start phase complete; required for readiness to go up. */
        void markKernelStarted();

        /** Marks the kernel failed; drops liveness. */
        void markKernelFailed();

        /**
         * Marks a registered subsystem RUNNING, clearing any prior failed or degraded state.
         *
         * @param subsystem the registered subsystem name
         */
        void markSubsystemRunning(String subsystem);

        /**
         * Marks a registered subsystem FAILED; drops readiness if the subsystem was
         * registered as required, and has no effect on readiness otherwise.
         *
         * @param subsystem the registered subsystem name
         */
        void markSubsystemFailed(String subsystem);

        /**
         * Marks a registered subsystem DEGRADED; drops readiness (without dropping
         * liveness) if the subsystem was registered as required, and has no effect on
         * readiness otherwise.
         *
         * @param subsystem the registered subsystem name
         */
        void markSubsystemDegraded(String subsystem);

        /**
         * Reports whether the kernel is ready to serve traffic.
         *
         * @return {@code true} if the kernel has started and every required subsystem is
         *         RUNNING
         */
        boolean readinessUp();

        /**
         * Reports whether the kernel is alive.
         *
         * @return {@code true} unless the kernel has been marked failed
         */
        boolean livenessUp();

        /**
         * Diagnostic readiness status string (e.g. {@code "READY"}, {@code "STARTING"},
         * {@code "DEGRADED"}, {@code "FAILED"}).
         *
         * @return the current readiness status string
         */
        String readinessStatus();
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
    @DisplayName("required subsystem degraded drops readiness with DEGRADED status, liveness stays UP")
    void requiredDegradedDropsReadinessButNotLiveness() {
        HealthMonitorAdapter monitor = createMonitor();
        monitor.register("persistence", true);
        monitor.markSubsystemRunning("persistence");
        monitor.markKernelInitialized();
        monitor.markKernelStarted();
        monitor.markSubsystemDegraded("persistence");

        assertThat(monitor.readinessUp()).isFalse();
        assertThat(monitor.readinessStatus()).isEqualTo("DEGRADED");
        assertThat(monitor.livenessUp()).isTrue();
    }

    @Test
    @DisplayName("optional subsystem degraded does not drop readiness")
    void optionalDegradedDoesNotDropReadiness() {
        HealthMonitorAdapter monitor = createMonitor();
        monitor.register("persistence", true);
        monitor.register("events", false);
        monitor.markSubsystemRunning("persistence");
        monitor.markKernelInitialized();
        monitor.markKernelStarted();
        monitor.markSubsystemDegraded("events");

        assertThat(monitor.readinessUp()).isTrue();
        assertThat(monitor.readinessStatus()).isEqualTo("READY");
    }

    @Test
    @DisplayName("degraded required subsystem recovers to READY (reversible)")
    void degradedSubsystemRecoversToReady() {
        HealthMonitorAdapter monitor = createMonitor();
        monitor.register("persistence", true);
        monitor.markSubsystemRunning("persistence");
        monitor.markKernelInitialized();
        monitor.markKernelStarted();

        monitor.markSubsystemDegraded("persistence");
        assertThat(monitor.readinessUp()).isFalse();

        monitor.markSubsystemRunning("persistence");
        assertThat(monitor.readinessUp()).isTrue();
        assertThat(monitor.readinessStatus()).isEqualTo("READY");
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

