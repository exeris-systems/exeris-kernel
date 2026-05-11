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
import eu.exeris.kernel.community.flow.JdbcFlowSnapshotStore;
import eu.exeris.kernel.core.flow.FlowBootstrap;
import eu.exeris.kernel.spi.bootstrap.BootstrapPhase;
import eu.exeris.kernel.spi.config.ConfigProvider;
import eu.exeris.kernel.spi.context.KernelProviders;
import eu.exeris.kernel.spi.flow.FlowEngine;
import eu.exeris.kernel.spi.flow.FlowEngineConfig;
import eu.exeris.kernel.spi.flow.FlowProvider;
import eu.exeris.kernel.spi.flow.model.FlowSnapshotStore;
import eu.exeris.kernel.spi.persistence.PersistenceEngine;

import java.util.ArrayList;
import java.util.List;
import java.util.function.UnaryOperator;

final class CommunityFlowSubsystem extends AbstractCommunitySubsystem {

    private FlowProvider flowProvider;
    private FlowEngine flowEngine;
    private FlowSnapshotStore snapshotStore;

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
            snapshotStore = resolveSnapshotStore(config.engineName());
        }
    }

    /**
     * Selects the JDBC-backed {@link JdbcFlowSnapshotStore} when a {@link PersistenceEngine}
     * was bound by {@link CommunityPersistenceSubsystem} during the SERVICES phase, otherwise
     * falls back to the heap {@link CommunityFlowSnapshotStore}. The handoff happens through
     * the Community-internal {@link CommunityBootstrapServices} registry — {@code
     * KernelProviders.PERSISTENCE_ENGINE} is not yet visible at this point in bootstrap
     * because {@code providerBindings()} composition runs after every subsystem's
     * {@code initialize()} (see ADR-022 §4 and Subsystem SPI Javadoc).
     */
    private static FlowSnapshotStore resolveSnapshotStore(String engineName) {
        PersistenceEngine engine = CommunityBootstrapServices.getSharedPersistenceEngine();
        if (engine != null) {
            return new JdbcFlowSnapshotStore(engine, engineName);
        }
        return new CommunityFlowSnapshotStore();
    }

    @Override
    public void start() {
        if (flowEngine == null) {
            return;
        }
        flowEngine.start();
        markRunning(true);
    }

    @Override
    public void stop() {
        markRunning(false);
        if (flowEngine != null) {
            flowEngine.close();
        }
    }

    @Override
    public UnaryOperator<ScopedValue.Carrier> providerBindings() {
        if (flowProvider == null || flowEngine == null) {
            return defaultProviderBindings();
        }
        List<CommunityCarrierBindings.Binding<?>> bindings = new ArrayList<>();
        bindings.add(CommunityCarrierBindings.binding(KernelProviders.FLOW_PROVIDER, flowProvider));
        bindings.add(CommunityCarrierBindings.binding(KernelProviders.FLOW_ENGINE, flowEngine));
        if (snapshotStore != null) {
            bindings.add(CommunityCarrierBindings.binding(KernelProviders.FLOW_SNAPSHOT_STORE, snapshotStore));
        }
        return CommunityCarrierBindings.operator(bindings);
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