/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.core.flow;

import eu.exeris.kernel.spi.events.EventBus;
import eu.exeris.kernel.spi.events.SubscriptionToken;
import eu.exeris.kernel.spi.flow.FlowChoreographyMapper;
import eu.exeris.kernel.spi.flow.FlowEngine;
import eu.exeris.kernel.spi.flow.FlowEngineCapabilities;
import eu.exeris.kernel.spi.flow.FlowEngineConfig;
import eu.exeris.kernel.spi.flow.FlowEngineStats;
import eu.exeris.kernel.spi.flow.FlowExecutionPlanFactory;
import eu.exeris.kernel.spi.flow.FlowRegistry;
import eu.exeris.kernel.spi.flow.FlowScheduler;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Heap-backed core flow engine shared by Community and future thin provider bindings.
 */
public final class CoreFlowEngine implements FlowEngine {

    private final FlowEngineCapabilities capabilities;
    private final CoreFlowRegistry registry;
    private final CoreFlowPlanFactory planFactory;
    private final CoreFlowRuntime runtime;
    private final List<Map.Entry<EventBus, SubscriptionToken>> choreographySubscriptions = new ArrayList<>();

    public CoreFlowEngine(FlowEngineConfig config, FlowEngineCapabilities capabilities) {
        FlowEngineConfig nonNullConfig = Objects.requireNonNull(config, "config");
        this.capabilities = Objects.requireNonNull(capabilities, "capabilities");
        this.registry = new CoreFlowRegistry();
        this.runtime = new CoreFlowRuntime(nonNullConfig, new FlowProgressPublisher());
        this.planFactory = new CoreFlowPlanFactory(nonNullConfig, registry, runtime.planCatalog());
    }

    @Override
    public FlowExecutionPlanFactory plans() {
        return planFactory;
    }

    @Override
    public FlowScheduler scheduler() {
        ensureStarted();
        return runtime.scheduler();
    }

    @Override
    public FlowRegistry registry() {
        return registry;
    }

    @Override
    public FlowEngineCapabilities capabilities() {
        return capabilities;
    }

    @Override
    public FlowEngineStats stats() {
        return runtime.stats();
    }

    @Override
    public void start() {
        runtime.start();
    }

    @Override
    public void close() {
        for (Map.Entry<EventBus, SubscriptionToken> entry : choreographySubscriptions) {
            try {
                entry.getKey().unsubscribe(entry.getValue());
            } catch (RuntimeException _) {
                // best-effort unsubscribe
            }
        }
        choreographySubscriptions.clear();
        runtime.close();
    }

    @Override
    public void registerChoreographyMapper(
            FlowChoreographyMapper mapper,
            Collection<String> eventTypeNames,
            EventBus bus) {
        if (!capabilities.choreographySupport()) {
            throw new UnsupportedOperationException("Choreography is not supported by this engine configuration");
        }
        ensureStarted();
        Objects.requireNonNull(mapper, "mapper");
        Objects.requireNonNull(eventTypeNames, "eventTypeNames");
        Objects.requireNonNull(bus, "bus");
        if (eventTypeNames.isEmpty()) {
            throw new IllegalArgumentException("eventTypeNames must not be empty");
        }
        FlowChoreographyBridge bridge = new FlowChoreographyBridge(mapper, runtime.scheduler());
        for (String eventType : eventTypeNames) {
            choreographySubscriptions.add(Map.entry(bus, bus.subscribe(eventType, bridge)));
        }
    }

    private void ensureStarted() {
        runtime.assertStarted();
    }
}
