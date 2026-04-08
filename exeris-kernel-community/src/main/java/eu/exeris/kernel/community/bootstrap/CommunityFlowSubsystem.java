/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.community.bootstrap;

import eu.exeris.kernel.community.flow.CommunityFlowSnapshotStore;
import eu.exeris.kernel.core.flow.FlowBootstrap;
import eu.exeris.kernel.spi.bootstrap.BootstrapPhase;
import eu.exeris.kernel.spi.bootstrap.Subsystem;
import eu.exeris.kernel.spi.config.ConfigProvider;
import eu.exeris.kernel.spi.context.KernelProviders;
import eu.exeris.kernel.spi.flow.FlowEngine;
import eu.exeris.kernel.spi.flow.FlowEngineConfig;
import eu.exeris.kernel.spi.flow.FlowProvider;
import eu.exeris.kernel.spi.flow.model.FlowSnapshotStore;

import java.util.List;
import java.util.function.UnaryOperator;

final class CommunityFlowSubsystem implements Subsystem {

    private FlowProvider flowProvider;
    private FlowEngine flowEngine;
    private FlowSnapshotStore snapshotStore;
    private boolean running;

    @Override
    public String name() {
        return "flow";
    }

    @Override
    public List<String> dependsOn() {
        return List.of("persistence");
    }

    @Override
    public BootstrapPhase phase() {
        return BootstrapPhase.RUNTIME;
    }

    @Override
    public void initialize() {
        ConfigProvider configProvider = KernelProviders.CURRENT_CONFIG.get();
        FlowEngineConfig config = buildFlowConfig(configProvider);
        FlowBootstrap.BootstrapResult bootstrap = FlowBootstrap.loadWithProvider(config);
        flowProvider = bootstrap.provider();
        flowEngine = bootstrap.engine();
        if (config.persistenceEnabled()) {
            snapshotStore = new CommunityFlowSnapshotStore();
        }
    }

    @Override
    public void start() {
        if (flowEngine == null) {
            return;
        }
        flowEngine.start();
        running = true;
    }

    @Override
    public void stop() {
        running = false;
        if (flowEngine != null) {
            flowEngine.close();
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public UnaryOperator<ScopedValue.Carrier> providerBindings() {
        if (flowProvider == null || flowEngine == null) {
            return Subsystem.super.providerBindings();
        }
        return carrier -> {
            ScopedValue.Carrier bound = carrier
                    .where(KernelProviders.FLOW_PROVIDER, flowProvider)
                    .where(KernelProviders.FLOW_ENGINE, flowEngine);
            if (snapshotStore != null) {
                bound = bound.where(KernelProviders.FLOW_SNAPSHOT_STORE, snapshotStore);
            }
            return bound;
        };
    }

    private static FlowEngineConfig buildFlowConfig(ConfigProvider configProvider) {
        FlowEngineConfig defaults = FlowEngineConfig.defaults("Community/HeapFlow");
        return new FlowEngineConfig(
                configProvider.getString("flow.engineName").orElse(defaults.engineName()),
                configProvider.getInt("flow.maxConcurrentFlows").orElse(defaults.maxConcurrentFlows()),
                configProvider.getLong("flow.timeoutDurationNanos").orElse(defaults.timeoutDurationNanos()),
                configProvider.getInt("flow.maxSteps").orElse(defaults.maxSteps()),
                configProvider.getInt("flow.maxTransitions").orElse(defaults.maxTransitions()),
                configProvider.getInt("flow.maxExecutionPlans").orElse(defaults.maxExecutionPlans()),
                configProvider.getInt("flow.schedulerQueueCapacity").orElse(defaults.schedulerQueueCapacity()),
                defaults.partitionName(),
                defaults.partitionBytes(),
                configProvider.getBoolean("flow.persistenceEnabled").orElse(defaults.persistenceEnabled()),
                configProvider.getBoolean("flow.compensationEnabled").orElse(defaults.compensationEnabled())
        );
    }
}