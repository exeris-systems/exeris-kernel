/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.core.bootstrap;

import eu.exeris.kernel.core.bootstrap.health.KernelHealthMonitor;
import eu.exeris.kernel.tck.contract.bootstrap.AbstractHealthMonitorTck;
import org.junit.jupiter.api.DisplayName;

/** Core binding for {@link AbstractHealthMonitorTck}. */
@DisplayName("Core: KernelHealthMonitor TCK")
class CoreHealthMonitorTckTest extends AbstractHealthMonitorTck {

    @Override
    protected HealthMonitorAdapter createMonitor() {
        KernelHealthMonitor monitor = new KernelHealthMonitor();
        return new HealthMonitorAdapter() {
            @Override
            public void register(String subsystem, boolean required) {
                monitor.registerSubsystem(subsystem, required);
            }

            @Override
            public void markKernelInitialized() {
                monitor.markKernelState(KernelHealthMonitor.KernelState.INITIALIZED);
            }

            @Override
            public void markKernelStarted() {
                monitor.markKernelState(KernelHealthMonitor.KernelState.STARTED);
            }

            @Override
            public void markKernelFailed() {
                monitor.markKernelState(KernelHealthMonitor.KernelState.FAILED);
            }

            @Override
            public void markSubsystemRunning(String subsystem) {
                monitor.markSubsystemState(subsystem, KernelHealthMonitor.SubsystemState.RUNNING);
            }

            @Override
            public void markSubsystemFailed(String subsystem) {
                monitor.markSubsystemState(subsystem, KernelHealthMonitor.SubsystemState.FAILED);
            }

            @Override
            public void markSubsystemDegraded(String subsystem) {
                monitor.markSubsystemState(subsystem, KernelHealthMonitor.SubsystemState.DEGRADED);
            }

            @Override
            public boolean readinessUp() {
                return monitor.readiness().healthy();
            }

            @Override
            public boolean livenessUp() {
                return monitor.liveness().healthy();
            }

            @Override
            public String readinessStatus() {
                return monitor.readiness().status();
            }
        };
    }
}


