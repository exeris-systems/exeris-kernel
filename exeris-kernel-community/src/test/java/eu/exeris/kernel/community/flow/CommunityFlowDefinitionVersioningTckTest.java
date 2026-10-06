/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.flow;

import eu.exeris.kernel.spi.flow.FlowEngine;
import eu.exeris.kernel.spi.flow.FlowEngineConfig;
import eu.exeris.kernel.spi.flow.model.FlowSnapshotStore;
import eu.exeris.kernel.tck.contract.flow.AbstractFlowDefinitionVersioningTck;
import org.junit.jupiter.api.DisplayName;

/** Community binding of {@link AbstractFlowDefinitionVersioningTck}. */
@DisplayName("Community: Flow definition versioning TCK")
class CommunityFlowDefinitionVersioningTckTest extends AbstractFlowDefinitionVersioningTck {

    private final CommunityFlowProvider provider = new CommunityFlowProvider();
    private final FlowSnapshotStore sharedStore = new CommunityFlowSnapshotStore();

    @Override
    protected FlowEngine createEngine(FlowSnapshotStore store) {
        return new ScopedStoreFlowEngine(provider.createEngine(persistenceConfig()), store);
    }

    @Override
    protected FlowSnapshotStore snapshotStore() {
        return sharedStore;
    }

    private static FlowEngineConfig persistenceConfig() {
        FlowEngineConfig d = FlowEngineConfig.defaults("Community/VersioningTck");
        return new FlowEngineConfig(
                d.engineName(), d.maxConcurrentFlows(), d.timeoutDurationNanos(), d.maxSteps(),
                d.maxTransitions(), d.maxExecutionPlans(), d.schedulerQueueCapacity(),
                d.partitionName(), d.partitionBytes(), true, d.compensationEnabled());
    }
}
