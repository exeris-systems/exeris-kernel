/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.core.flow.tck;

import eu.exeris.kernel.core.flow.CoreFlowEngine;
import eu.exeris.kernel.spi.flow.FlowEngine;
import eu.exeris.kernel.spi.flow.FlowEngineCapabilities;
import eu.exeris.kernel.spi.flow.FlowEngineConfig;
import eu.exeris.kernel.spi.flow.model.FlowSnapshot;
import eu.exeris.kernel.spi.flow.model.FlowSnapshotStore;
import eu.exeris.kernel.tck.contract.flow.AbstractSagaRecoveryTck;
import org.junit.jupiter.api.DisplayName;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * TCK binding: Saga recovery and persistence contract verification.
 * Tests: mid-saga kill &amp; resume, idempotency on double-schedule, compensation flow.
 *
 * @since 0.5.0
 */
@DisplayName("Core: Saga Recovery TCK")
class CoreSagaRecoveryTckTest extends AbstractSagaRecoveryTck {

    private final InMemorySnapshotStore sharedStore = new InMemorySnapshotStore();

    @Override
    protected FlowEngine createEngine() {
        FlowEngineConfig config = persistenceConfig();
        CoreFlowEngine engine = new CoreFlowEngine(config, 
                FlowEngineCapabilities.COMMUNITY.withProvider("core-saga-recovery-tck"));
        return new ScopedStoreFlowEngine(engine, sharedStore);
    }

    @Override
    protected FlowEngine rebuildEngine() {
        FlowEngineConfig config = persistenceConfig();
        CoreFlowEngine engine = new CoreFlowEngine(config, 
                FlowEngineCapabilities.COMMUNITY.withProvider("core-saga-recovery-tck-rebuild"));
        return new ScopedStoreFlowEngine(engine, sharedStore);
    }

    @Override
    protected FlowSnapshotStore snapshotStore() {
        return sharedStore;
    }

    private static FlowEngineConfig persistenceConfig() {
        FlowEngineConfig defaults = FlowEngineConfig.defaults("CoreSagaRecovery/TCK");
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
                true,  // persistenceEnabled
                defaults.compensationEnabled()
        );
    }

    /**
     * In-memory snapshot store for TCK bindings.
     * Shared across engine instances to simulate persistence across restarts.
     */
    private static final class InMemorySnapshotStore implements FlowSnapshotStore {
        private final ConcurrentMap<FlowSnapshotKey, FlowSnapshot> snapshots = new ConcurrentHashMap<>();

        @Override
        public void save(FlowSnapshot snapshot) {
            snapshots.put(new FlowSnapshotKey(snapshot.instanceIdMost(), snapshot.instanceIdLeast()), snapshot);
        }

        @Override
        public Optional<FlowSnapshot> load(long instanceIdMost, long instanceIdLeast) {
            return Optional.ofNullable(snapshots.get(new FlowSnapshotKey(instanceIdMost, instanceIdLeast)));
        }

        @Override
        public void delete(long instanceIdMost, long instanceIdLeast) {
            snapshots.remove(new FlowSnapshotKey(instanceIdMost, instanceIdLeast));
        }

        @Override
        public boolean exists(long instanceIdMost, long instanceIdLeast) {
            return snapshots.containsKey(new FlowSnapshotKey(instanceIdMost, instanceIdLeast));
        }
    }

    /**
     * Composite key for snapshot indexing.
     */
    private record FlowSnapshotKey(long instanceIdMost, long instanceIdLeast) {
    }
}
