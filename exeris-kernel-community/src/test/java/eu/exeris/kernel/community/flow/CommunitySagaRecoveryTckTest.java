/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.community.flow;

import eu.exeris.kernel.spi.flow.FlowEngine;
import eu.exeris.kernel.spi.flow.FlowEngineConfig;
import eu.exeris.kernel.spi.flow.model.FlowSnapshotStore;
import eu.exeris.kernel.tck.contract.flow.AbstractSagaRecoveryTck;
import org.junit.jupiter.api.DisplayName;

@DisplayName("Community: Saga recovery TCK")
class CommunitySagaRecoveryTckTest extends AbstractSagaRecoveryTck {

    private final CommunityFlowProvider provider = new CommunityFlowProvider();
    private final FlowSnapshotStore sharedStore = new CommunityFlowSnapshotStore();

    @Override
    protected FlowEngine createEngine() {
        return new ScopedStoreFlowEngine(provider.createEngine(persistenceConfig()), sharedStore);
    }

    @Override
    protected FlowEngine rebuildEngine() {
        return new ScopedStoreFlowEngine(provider.createEngine(persistenceConfig()), sharedStore);
    }

    @Override
    protected FlowSnapshotStore snapshotStore() {
        return sharedStore;
    }

    private static FlowEngineConfig persistenceConfig() {
        FlowEngineConfig defaults = FlowEngineConfig.defaults("Community/HeapFlow");
        return new FlowEngineConfig(
                defaults.engineName(),
                defaults.maxConcurrentFlows(),
                defaults.timeoutDurationNanos(),
                defaults.maxSteps(),
                defaults.maxTransitions(),
                defaults.maxExecutionPlans(),
                defaults.schedulerQueueCapacity(),
                defaults.partitionName(),
                defaults.partitionBytes(),
                true,
                defaults.compensationEnabled()
        );
    }
}