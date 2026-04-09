/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.spi.flow;

import eu.exeris.kernel.spi.events.EventBus;
import eu.exeris.kernel.spi.exceptions.flow.FlowEngineException;

import java.util.Collection;

/**
 * SPI: Central Flow Engine facade — the primary runtime interface for all flow
 * orchestration operations.
 *
 * <h2>Composite Design</h2>
 * <p>The engine is a single point of access to all flow subsystem components.
 * It is obtained via {@link FlowProvider#createEngine} and propagated to all
 * subsystems via {@link eu.exeris.kernel.spi.context.KernelProviders#FLOW_ENGINE}.
 *
 * <h2>Lifecycle</h2>
 * <ol>
 *   <li>{@link FlowProvider#createEngine} — created by the bootstrapper (no I/O, no threads).</li>
 *   <li>{@link #start()} — initialises all components (allocates resources, pre-warms slab pools).</li>
 *   <li>Runtime — subsystems call {@link #plans()}, {@link #scheduler()}, etc.</li>
 *   <li>{@link #close()} — graceful shutdown (drains pending flows, releases memory).</li>
 * </ol>
 *
 * <h2>Tier Behaviour</h2>
 * <ul>
 *   <li><b>Community</b>: heap-based components, {@code StructuredTaskScope}-style scheduler,
 *       no ordering guarantees. Any flow-local memory segments MUST be allocated via the
 *       tier's {@link eu.exeris.kernel.spi.memory.MemoryAllocator} and accessed through
 *       {@link eu.exeris.kernel.spi.context.KernelProviders} so that segments remain safely
 *       usable when flow steps execute on different virtual threads than the allocating
 *       context (no thread-affinity side effects).</li>
 *   <li><b>Enterprise</b>: all state in pre-allocated segments. Scheduler uses a lock-free
 *       ring buffer with cache-line-isolated head/tail counters to eliminate false sharing.
 *       {@link eu.exeris.kernel.spi.flow.model.FlowContext} is a Flyweight — one reusable
 *       per-carrier view slides its base address over the context segment;
 *       no new object is created per dispatch, preserving the Zero-GC contract.</li>
 * </ul>
 *
 * @since 0.5.0
 * @see FlowExecutionPlanFactory
 * @see FlowScheduler
 * @see FlowRegistry
 */
public interface FlowEngine extends AutoCloseable {

    /**
     * Returns the {@link FlowExecutionPlanFactory} — the single entry point for
     * both constructing {@link eu.exeris.kernel.spi.flow.model.FlowDefinition}s
     * (via {@link FlowExecutionPlanFactory#newDefinition(String)}) and compiling
     * them into executable {@link eu.exeris.kernel.spi.flow.model.FlowExecutionPlan}s
     * (via {@link FlowExecutionPlanFactory#compile}).
     *
     * <p>Replaces the former {@code builder()} and {@code execution()} methods, which
     * exposed two interfaces with identical {@code compile()} signatures (DRY violation).
     * Available after {@link #start()} returns.
     */
    FlowExecutionPlanFactory plans();

    /**
     * Returns the {@link FlowScheduler} for schedule/park/wake operations.
     *
     * <p>Community: {@code StructuredTaskScope}-based virtual-thread dispatcher.
     * Enterprise: lock-free ring buffer with cache-line-isolated head/tail counters.
     */
    FlowScheduler scheduler();

    /**
     * Returns the {@link FlowRegistry} for step and transition registration.
     *
     * <p>Community: heap array-backed registry indexed by {@code stepId}, pre-sized to
     * {@link FlowEngineConfig#maxSteps()} at engine start for allocation-free O(1) lookups
     * — no {@code Integer} boxing, no hash computation.
     * Enterprise: off-heap slab array, O(1) guaranteed via direct address arithmetic.
     *
     * @see FlowRegistry
     */
    FlowRegistry registry();


    /**
     * Returns the immutable capability descriptor.
     * Implementations MUST return a pre-built constant — not a freshly allocated record.
     */
    FlowEngineCapabilities capabilities();

    /** Returns a point-in-time snapshot of engine statistics. Diagnostic path only. */
    FlowEngineStats stats();

    /**
     * Registers a choreography mapper that translates incoming event descriptors to
     * flow scheduling decisions (event-driven Saga coordination).
     *
     * <p>For each name in {@code eventTypeNames}, the engine subscribes a
     * {@link FlowChoreographyMapper}-backed handler to the provided {@link EventBus}.
     * Subsequent calls ADD additional mappers; they do NOT replace existing registrations.
     * Subscription tokens are owned by the engine and cancelled on {@link #close()}.
     *
     * @param mapper         maps descriptors to decisions; must not be {@code null}
     * @param eventTypeNames event type names to subscribe to; must not be {@code null} or empty
     * @param bus            the {@link EventBus} on which to subscribe; must not be {@code null}
     * @throws UnsupportedOperationException if choreography is not supported
     * @since 0.6.0
     */
    default void registerChoreographyMapper(
            FlowChoreographyMapper mapper,
            Collection<String> eventTypeNames,
            EventBus bus) {
        throw new UnsupportedOperationException(
                "Choreography registration is not supported by this FlowEngine implementation: "
                + getClass().getName());
    }

    /**
     * Starts all engine components.
     *
     * <p>Enterprise: claims the flow memory partition via
     * {@link eu.exeris.kernel.spi.memory.MemoryAllocator#allocateInfrastructure},
     * pre-allocates all slab pools. Zero heap allocations after this call.
     * Emits a {@code FlowEngineBootstrapEvent} JFR event on completion.
     *
     * @throws FlowEngineException if startup fails
     */
    void start();

    /**
     * Gracefully shuts down the engine, draining pending flows and releasing off-heap memory.
     * Emits a {@code FlowEngineShutdownEvent} JFR event.
     */
    @Override
    void close();
}
