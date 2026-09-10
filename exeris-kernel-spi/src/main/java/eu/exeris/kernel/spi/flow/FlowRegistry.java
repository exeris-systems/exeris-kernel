/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.spi.flow;

import eu.exeris.kernel.spi.flow.model.FlowStepDescriptor;
import eu.exeris.kernel.spi.flow.model.FlowTransitionDescriptor;

/**
 * SPI: Registers and resolves flow step and transition descriptors.
 *
 * <p><b>Allocation:</b> zero-alloc on hot path — {@link #lookupStep(int)} and
 * {@link #lookupTransitions(int)} allocate nothing; in the reference binding the backing storage
 * grows as steps are registered (an {@code Arrays.copyOf} per call) rather than being pre-sized to
 * {@link FlowEngineConfig#maxSteps()} when the engine starts.
 * <p><b>Thread confinement:</b> lookups are safe from any flow thread; after
 * {@link FlowEngine#start()} they are lock-free, but the registry's contents are not read-only —
 * {@link FlowExecutionPlanFactory#compile} may still replace them when it registers a new plan or
 * version.
 * <p><b>Ownership:</b> the registry owns every descriptor registered into it and every array it
 * hands out — {@link #lookupTransitions(int)} may return internal storage — so a caller treats what
 * it receives as immutable and copies before mutating. Nothing here is released by the caller.
 *
 * @implSpec Both {@link #lookupStep(int)} and {@link #lookupTransitions(int)} must execute in O(1)
 *           time; an O(n) scan on the hot execution path is a hard rejection.
 * @implNote The Community binding is a pair of heap arrays ({@code FlowStepDescriptor[]} and
 *           {@code FlowTransitionDescriptor[][]}) indexed directly by {@code stepId}, which is a
 *           zero-based index per the
 *           {@link eu.exeris.kernel.spi.flow.model.FlowStepDescriptor#stepId()} contract — so
 *           access is O(1) and allocation-free, with no {@code Integer} boxing and no hash
 *           computation. It synchronises registration during bootstrap only; reads after
 *           {@link FlowEngine#start()} are lock-free. An implementation that populates the
 *           registry through {@link #registerStep} and {@link #registerTransition} before start
 *           may enforce that ordering, for example by throwing {@link IllegalStateException} for a
 *           post-start call; the Community binding instead repopulates the registry from
 *           {@link FlowExecutionPlanFactory#compile}, including after {@link FlowEngine#start()}
 *           has returned, bypassing these two methods entirely. The Enterprise binding is an
 *           off-heap slab array addressed as {@code baseAddr + stepId * STEP_DESCRIPTOR_STRIDE},
 *           written through
 *           {@code MemorySegment.set(ValueLayout, offset, value)} with a {@code VarHandle} for
 *           acquire/release ordering — no {@code sun.misc.Unsafe}.
 * @since 0.5
 * @see FlowStepDescriptor
 * @see FlowTransitionDescriptor
 */
public interface FlowRegistry {

    /**
     * Binds a step descriptor to its {@link FlowStepDescriptor#stepId()}, so the execution path can
     * resolve it by that id without a scan.
     *
     * @param step the step descriptor to register; must not be {@code null}
     * @throws eu.exeris.kernel.spi.exceptions.flow.FlowRegistryException {@code EX-FLOW-7004} with
     *         {@code staticReasonCode="DUPLICATE_STEP"} if a step with the same
     *         {@link FlowStepDescriptor#stepId()} is already registered — the id is not rebound
     * @throws IllegalStateException if called after {@link FlowEngine#start()}
     */
    void registerStep(FlowStepDescriptor step);

    /**
     * Records an edge between two registered steps, which is what
     * {@link #lookupTransitions(int)} returns to the engine when it decides where a completed step
     * goes next.
     *
     * @param transition the transition descriptor to register; must not be {@code null}
     * @throws eu.exeris.kernel.spi.exceptions.flow.FlowRegistryException {@code EX-FLOW-7004} if
     *         the transition references an unknown step id
     * @throws IllegalStateException if called after {@link FlowEngine#start()}
     */
    void registerTransition(FlowTransitionDescriptor transition);

    /**
     * Resolves the descriptor the engine will execute for this step id.
     *
     * @param stepId the step identifier
     * @return the registered descriptor; never {@code null}
     * @throws eu.exeris.kernel.spi.exceptions.flow.FlowRegistryException {@code EX-FLOW-7004} with
     *         {@code staticReasonCode="STEP_NOT_FOUND"} if no step with the given id is registered
     * @implSpec O(1), and allocation-free — this is on the hot execution path.
     */
    FlowStepDescriptor lookupStep(int stepId);

    /**
     * Resolves every edge leaving the given step, which is how the engine chooses the next step
     * rather than falling through to {@code fromStep + 1}.
     *
     * @param fromStep the source step id
     * @return the outgoing transitions; never {@code null}, and empty when the step declares none.
     *         The array belongs to the registry: it may be internal storage shared with every other
     *         caller, so treat it as immutable and {@code Arrays.copyOf} it before mutating —
     *         writing into it would silently corrupt the registry
     * @implSpec O(1), and allocation-free — an implementation may hand back a direct reference to
     *           internal storage precisely to avoid a hot-path copy.
     */
    FlowTransitionDescriptor[] lookupTransitions(int fromStep);
}

