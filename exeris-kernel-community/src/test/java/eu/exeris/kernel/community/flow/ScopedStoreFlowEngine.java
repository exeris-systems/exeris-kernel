/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.community.flow;

import eu.exeris.kernel.spi.context.KernelProviders;
import eu.exeris.kernel.spi.flow.FlowEngine;
import eu.exeris.kernel.spi.flow.FlowEngineCapabilities;
import eu.exeris.kernel.spi.flow.FlowEngineStats;
import eu.exeris.kernel.spi.flow.FlowExecutionPlanFactory;
import eu.exeris.kernel.spi.flow.FlowRegistry;
import eu.exeris.kernel.spi.flow.FlowScheduler;
import eu.exeris.kernel.spi.flow.model.FlowSnapshotStore;

final class ScopedStoreFlowEngine implements FlowEngine {

    private final FlowEngine delegate;
    private final FlowSnapshotStore snapshotStore;

    ScopedStoreFlowEngine(FlowEngine delegate, FlowSnapshotStore snapshotStore) {
        this.delegate = delegate;
        this.snapshotStore = snapshotStore;
    }

    @Override
    public FlowExecutionPlanFactory plans() {
        return delegate.plans();
    }

    @Override
    public FlowScheduler scheduler() {
        return delegate.scheduler();
    }

    @Override
    public FlowRegistry registry() {
        return delegate.registry();
    }

    @Override
    public FlowEngineCapabilities capabilities() {
        return delegate.capabilities();
    }

    @Override
    public FlowEngineStats stats() {
        return delegate.stats();
    }

    @Override
    public void start() {
        ScopedValue.where(KernelProviders.FLOW_SNAPSHOT_STORE, snapshotStore).run(delegate::start);
    }

    @Override
    public void close() {
        delegate.close();
    }
}