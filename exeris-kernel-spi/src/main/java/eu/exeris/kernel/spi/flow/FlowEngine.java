/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
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
 *   <li>{@link #close()} — interrupts in-flight flows and joins them within a bounded
 *       per-thread deadline, then releases memory. PARKED checkpoints persisted before
 *       {@code close()} survive for restart recovery; in-flight RUNNING progress past the
 *       last checkpoint may be lost.</li>
 * </ol>
 *
 * <p><b>Allocation:</b> allocates at {@link #start()} — component initialisation and, in a tier
 * that claims one, the flow memory partition and its slab pools. {@link #capabilities()} allocates
 * nothing, since it must hand back a pre-built constant; {@link #stats()} yields a snapshot record
 * and belongs on the diagnostic path rather than the dispatch loop.
 * <p><b>Ownership:</b> the bootstrapper that obtained the engine from
 * {@link FlowProvider#createEngine} owns it and closes it on shutdown; {@link #close()} releases
 * the engine's memory and cancels every choreography subscription token the engine holds.
 *
 * @implSpec Flow-local memory segments must be allocated via the tier's
 *           {@link eu.exeris.kernel.spi.memory.MemoryAllocator} and reached through
 *           {@link eu.exeris.kernel.spi.context.KernelProviders}, so that a segment stays safely
 *           usable when a flow step runs on a different virtual thread than the context that
 *           allocated it — no thread-affinity side effects.
 * @implNote The Community binding holds its components on the heap behind a
 *           {@code StructuredTaskScope}-style scheduler and gives no ordering guarantee. The
 *           Enterprise binding keeps all state in pre-allocated segments, schedules through a
 *           lock-free ring buffer whose head and tail counters are cache-line isolated, and treats
 *           {@link eu.exeris.kernel.spi.flow.model.FlowContext} as a flyweight — one reusable
 *           per-carrier view slides its base address over the context segment, so no object is
 *           created per dispatch.
 * @since 0.5
 * @see FlowExecutionPlanFactory
 * @see FlowScheduler
 * @see FlowRegistry
 */
public interface FlowEngine extends AutoCloseable {

    /**
     * Returns the single entry point for both assembling
     * {@link eu.exeris.kernel.spi.flow.model.FlowDefinition}s (via
     * {@link FlowExecutionPlanFactory#newDefinition(String)}) and compiling them into executable
     * {@link eu.exeris.kernel.spi.flow.model.FlowExecutionPlan}s (via
     * {@link FlowExecutionPlanFactory#compile}).
     *
     * @return the plan factory bound to this engine; never {@code null}, and usable from the moment
     *         {@link #start()} returns until {@link #close()}
     */
    FlowExecutionPlanFactory plans();

    /**
     * Returns the component through which flows are submitted, parked and woken.
     *
     * @return the scheduler bound to this engine; never {@code null}, and usable from the moment
     *         {@link #start()} returns until {@link #close()}
     * @implNote The Community binding dispatches onto virtual threads through a
     *           {@code StructuredTaskScope}; the Enterprise binding enqueues into a lock-free ring
     *           buffer with cache-line-isolated head and tail counters.
     */
    FlowScheduler scheduler();

    /**
     * Returns the component that resolves a step or transition descriptor by id on the execution
     * path, and into which those descriptors are registered before {@link #start()}.
     *
     * @return the registry bound to this engine; never {@code null}
     * @implNote The Community binding is a heap array indexed directly by {@code stepId}, pre-sized
     *           to {@link FlowEngineConfig#maxSteps()} at engine start so lookups are O(1) and
     *           allocation-free — no {@code Integer} boxing, no hash computation. The Enterprise
     *           binding is an off-heap slab array addressed by arithmetic on the slab base.
     * @see FlowRegistry
     */
    FlowRegistry registry();


    /**
     * Reports what this engine binding actually supports — deterministic ordering, off-heap
     * descriptors, a lock-free scheduler, zero-GC after start, persistence, compensation and
     * choreography — so the bootstrapper can gate on it and record it in JFR.
     *
     * @return the capability descriptor of this engine; never {@code null}
     * @implSpec Implementations must return a pre-built constant, not a record allocated per call;
     *           {@link FlowEngineCapabilities#withProvider(String)} exists so a driver can brand a
     *           template once at class-load time.
     */
    FlowEngineCapabilities capabilities();

    /**
     * Reads the engine's counters — active, parked, completed and failed flows, compensations,
     * step executions and scheduler depth — as one consistent point-in-time record.
     *
     * @return a snapshot of the counters as they stood when the call was made; never {@code null}.
     *         The counters reflect the current lifecycle generation, so a {@link #close()} followed
     *         by a {@link #start()} restarts them from zero
     * @apiNote Diagnostic and monitoring path only — never call it from the dispatch loop.
     */
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
     * @throws UnsupportedOperationException if choreography is not supported — the same engines that
     *         throw here report {@link FlowEngineCapabilities#choreographySupport()} as
     *         {@code false}
     * @apiNote Check {@link #capabilities()}{@code .choreographySupport()} before registering if the
     *          application must run against an engine binding it did not choose.
     * @since 0.5
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
     * Brings every engine component up and opens the runtime: after this call returns,
     * {@link #plans()}, {@link #scheduler()} and {@link #registry()} are usable and the counters
     * behind {@link #stats()} start from zero for this lifecycle generation.
     *
     * @throws FlowEngineException {@code EX-FLOW-7002} with {@code phase="START"} and
     *         {@code reasonCode="STARTUP_FAILED"} if startup fails
     * @implNote The Enterprise binding claims the flow memory partition through
     *           {@link eu.exeris.kernel.spi.memory.MemoryAllocator#allocateInfrastructure} and
     *           pre-allocates every slab pool here, so that nothing is allocated on the heap
     *           afterwards. Completion is reported on a JFR bootstrap event.
     */
    void start();

    /**
     * Shuts down the engine: interrupts in-flight flows and joins each worker within a
     * bounded per-thread deadline, then releases off-heap memory. This is not an unbounded
     * graceful drain — PARKED checkpoints persisted before {@code close()} survive for
     * restart recovery, but in-flight RUNNING progress past the last checkpoint may be lost.
     * Emits a {@code FlowEngineShutdownEvent} JFR event.
     */
    @Override
    void close();
}
