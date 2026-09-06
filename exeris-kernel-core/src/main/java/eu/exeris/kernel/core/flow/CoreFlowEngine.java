/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
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
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Heap-backed core flow engine shared by Community and future thin provider bindings.
 *
 * <p>Assembles and owns the three components a {@link FlowEngine} exposes — a
 * {@link CoreFlowRegistry}, a {@link CoreFlowPlanFactory} and a {@link CoreFlowRuntime} — and adds
 * choreography subscription bookkeeping and idempotent shutdown on top of {@link CoreFlowRuntime}'s
 * own lifecycle.
 *
 * @implNote {@code choreographySubscriptions} is a plain {@link ArrayList}: {@link #close()}
 *           iterates and clears it while {@link #registerChoreographyMapper} appends to it, and
 *           neither is synchronized against the other, so a caller driving both from more than one
 *           thread must supply its own coordination.
 */
public final class CoreFlowEngine implements FlowEngine {

    private final FlowEngineConfig config;
    private final FlowEngineCapabilities capabilities;
    private final CoreFlowRegistry registry;
    private final CoreFlowPlanFactory planFactory;
    private final CoreFlowRuntime runtime;
    private final List<Map.Entry<EventBus, SubscriptionToken>> choreographySubscriptions = new ArrayList<>();
    private final AtomicBoolean closeInitiated = new AtomicBoolean();

    /**
     * Assembles the registry, plan factory and runtime that back this engine from {@code config},
     * without starting it — {@link #start()} must still be called before scheduling any flow.
     *
     * @param config the engine configuration; also handed to the backing {@link CoreFlowRuntime}
     * @param capabilities the capability descriptor this engine reports via {@link #capabilities()}
     * @throws NullPointerException if {@code config} or {@code capabilities} is {@code null}
     */
    public CoreFlowEngine(FlowEngineConfig config, FlowEngineCapabilities capabilities) {
        FlowEngineConfig nonNullConfig = Objects.requireNonNull(config, "config");
        this.config = nonNullConfig;
        this.capabilities = Objects.requireNonNull(capabilities, "capabilities");
        this.registry = new CoreFlowRegistry();
        this.runtime = new CoreFlowRuntime(nonNullConfig, new FlowProgressPublisher());
        this.planFactory = new CoreFlowPlanFactory(
                nonNullConfig,
                registry,
                runtime.planCatalog(),
                runtime.migrations(),
                runtime::clearLookupSuppressionAfterPlanCompile);
    }

    /**
     * {@inheritDoc}
     *
     * @implNote Returns the same {@link CoreFlowPlanFactory} instance for the life of this engine;
     *           unlike {@link #scheduler()} this does not require {@link #start()} to have been
     *           called first.
     */
    @Override
    public FlowExecutionPlanFactory plans() {
        return planFactory;
    }

    /**
     * {@inheritDoc}
     *
     * @implNote Delegates to {@link CoreFlowRuntime#scheduler()} after confirming the runtime has
     *           been started.
     */
    @Override
    public FlowScheduler scheduler() {
        ensureStarted();
        return runtime.scheduler();
    }

    /**
     * {@inheritDoc}
     *
     * @implNote Returns the same {@link CoreFlowRegistry} instance for the life of this engine, with
     *           no check that {@link #start()} has been called — {@link CoreFlowPlanFactory#compile}
     *           populates it independently of the engine's own started/closed state.
     */
    @Override
    public FlowRegistry registry() {
        return registry;
    }

    /**
     * {@inheritDoc}
     *
     * @implNote Returns the constant handed to the constructor; consistent with
     *           {@link FlowEngineCapabilities#withProvider(String)} branding a template once rather
     *           than this method allocating one per call.
     */
    @Override
    public FlowEngineCapabilities capabilities() {
        return capabilities;
    }

    /**
     * {@inheritDoc}
     *
     * @implNote Delegates to {@link CoreFlowRuntime#stats()}; callable regardless of whether
     *           {@link #start()} has run, since the underlying counters exist from construction.
     */
    @Override
    public FlowEngineStats stats() {
        return runtime.stats();
    }

    /**
     * {@inheritDoc}
     *
     * @implNote Clears the flag {@link #close()} uses to guard against a second shutdown, so an
     *           engine restarted after {@link #close()} accepts a later {@code close()} call again
     *           rather than treating it as already-closed. Delegates the rest of startup to
     *           {@link CoreFlowRuntime#start()}.
     */
    @Override
    public void start() {
        closeInitiated.set(false);
        runtime.start();
    }

    /**
     * {@inheritDoc}
     *
     * @implNote Idempotent via a compare-and-set on an internal flag — a second call returns
     *           immediately. Unsubscribes every choreography subscription this engine registered,
     *           tolerating a failed unsubscribe on any one {@link EventBus} so the remaining ones and
     *           the runtime shutdown still proceed, then closes {@link CoreFlowRuntime} and emits
     *           {@link FlowEngineShutdownEvent} with the observed shutdown duration.
     */
    @Override
    public void close() {
        if (!closeInitiated.compareAndSet(false, true)) {
            return;
        }
        long startNanos = System.nanoTime();
        for (Map.Entry<EventBus, SubscriptionToken> entry : choreographySubscriptions) {
            try {
                entry.getKey().unsubscribe(entry.getValue());
            } catch (RuntimeException _) { //NOPMD AvoidCatchingGenericException — best-effort unsubscribe at shutdown
                // best-effort unsubscribe
            }
        }
        choreographySubscriptions.clear();
        runtime.close();
        FlowEngineShutdownEvent.emit(config, runtime.stats(),
                runtime.nonDurableParkedFlows(), System.nanoTime() - startNanos);
    }

    /**
     * {@inheritDoc}
     *
     * @throws UnsupportedOperationException {@inheritDoc}
     * @implNote Requires {@link #start()} to have run. Wraps {@code mapper} and this engine's
     *           scheduler in one {@link FlowChoreographyBridge} shared across every {@code eventType}
     *           in {@code eventTypeNames}, and records each returned {@link SubscriptionToken} so
     *           {@link #close()} can unsubscribe it.
     */
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
