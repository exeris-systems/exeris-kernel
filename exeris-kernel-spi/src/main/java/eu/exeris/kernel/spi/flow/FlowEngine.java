/*
 * Copyright (C) 2025-2026 Exeris. All rights reserved.
 *
 * This code is part of the Exeris Systems.
 * Distributed under the proprietary Exeris Software License.
 * Unauthorized copying or distribution is prohibited.
 */
package eu.exeris.kernel.spi.flow;

import eu.exeris.kernel.spi.exceptions.flow.FlowEngineException;

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
 *   <li>Runtime — subsystems call {@link #builder()}, {@link #scheduler()}, etc.</li>
 *   <li>{@link #close()} — graceful shutdown (drains pending flows, releases memory).</li>
 * </ol>
 *
 * <h2>Tier Behaviour</h2>
 * <ul>
 *   <li><b>Community</b>: heap-based components, {@code StructuredTaskScope} scheduler,
 *       no ordering guarantees. Arena uses {@code Arena.ofShared()} to allow cross-virtual-thread
 *       segment access — prevents {@code WrongThreadException} when flow steps execute on
 *       different virtual threads than the allocating thread.</li>
 *   <li><b>Enterprise</b>: off-heap slab pools, lock-free ring buffer scheduler.
 *       The ring buffer's {@code head} and {@code tail} counter fields are annotated with
 *       {@code @jdk.internal.vm.annotation.Contended} and separated by 128 bytes of padding
 *       so that producer and consumer never share a cache line (false-sharing prevention).
 *       {@link eu.exeris.kernel.spi.flow.model.FlowContext} is a Flyweight interface — one
 *       reusable per-carrier view object slides its base address over the off-heap context
 *       slab; no new object is created per dispatch, preserving the Zero-GC contract.</li>
 * </ul>
 *
 * @since 0.5.0
 * @see FlowBuilder
 * @see FlowScheduler
 * @see FlowRegistry
 * @see FlowExecutionPlanFactory
 */
public interface FlowEngine extends AutoCloseable {

    /**
     * Returns the {@link FlowBuilder} for compiling flow definitions.
     * Available after {@link #start()} returns.
     */
    FlowBuilder builder();

    /**
     * Returns the {@link FlowScheduler} for schedule/park/wake operations.
     *
     * <p>Community: {@code StructuredTaskScope}-based virtual-thread dispatcher.
     * Enterprise: lock-free ring buffer with {@code @Contended} head/tail padding.
     */
    FlowScheduler scheduler();

    /**
     * Returns the {@link FlowRegistry} for step and transition registration.
     *
     * <p>Community: {@code HashMap}-backed, O(1) average.
     * Enterprise: off-heap slab array, O(1) guaranteed via direct address arithmetic.
     */
    FlowRegistry registry();

    /** Returns the {@link FlowExecutionPlanFactory} for compiling flow definitions. */
    FlowExecutionPlanFactory execution();

    /**
     * Returns the immutable capability descriptor.
     * Implementations MUST return a pre-built constant — not a freshly allocated record.
     */
    FlowEngineCapabilities capabilities();

    /** Returns a point-in-time snapshot of engine statistics. Diagnostic path only. */
    FlowEngineStats stats();

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
