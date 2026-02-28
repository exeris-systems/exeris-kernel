/*
 * Copyright (C) 2025-2026 Exeris. All rights reserved.
 *
 * This code is part of the Exeris Systems.
 * Distributed under the proprietary Exeris Software License.
 * Unauthorized copying or distribution is prohibited.
 */
package eu.exeris.kernel.spi.flow;

import eu.exeris.kernel.spi.flow.model.FlowStepDescriptor;
import eu.exeris.kernel.spi.flow.model.FlowTransitionDescriptor;

/**
 * SPI: Registers and resolves flow step and transition descriptors.
 *
 * <h2>Lookup Contract — O(1) mandatory</h2>
 * <p>Both {@link #lookupStep(int)} and {@link #lookupTransitions(int)} MUST execute in
 * O(1) time. Any O(n) scan on the hot execution path is a hard rejection.
 *
 * <h2>Tier Contract</h2>
 * <ul>
 *   <li><b>Community</b>: backed by a {@code HashMap<Integer, FlowStepDescriptor>} and
 *       a {@code HashMap<Integer, FlowTransitionDescriptor[]>}.
 *       O(1) average (hash lookup). Registration is thread-safe via synchronisation
 *       during bootstrap only — reads after {@link FlowEngine#start()} are lock-free.</li>
 *   <li><b>Enterprise</b>: backed by an off-heap slab array.
 *       Lookup: {@code address = baseAddr + stepId * STEP_DESCRIPTOR_STRIDE} — O(1)
 *       guaranteed, no indirection, no hash computation.
 *       Registration writes descriptors directly into the slab via
 *       {@code MemorySegment.set(ValueLayout, offset, value)} using {@code VarHandle}
 *       for acquire/release ordering. No {@code sun.misc.Unsafe}.</li>
 * </ul>
 *
 * <h2>Registration Window</h2>
 * <p>All {@link #registerStep} and {@link #registerTransition} calls MUST complete
 * before {@link FlowEngine#start()} is called. Registering after start is undefined
 * behaviour and implementations MAY throw {@link IllegalStateException}.
 *
 * @since 0.5.0
 * @see FlowStepDescriptor
 * @see FlowTransitionDescriptor
 */
public interface FlowRegistry {

    /**
     * Registers a step descriptor.
     *
     * @param step the step descriptor to register; must not be {@code null}
     * @throws eu.exeris.kernel.spi.exceptions.flow.FlowRegistryException if a step with
     *         the same {@link FlowStepDescriptor#stepId()} is already registered
     * @throws IllegalStateException if called after {@link FlowEngine#start()}
     */
    void registerStep(FlowStepDescriptor step);

    /**
     * Registers a transition descriptor.
     *
     * @param transition the transition descriptor to register; must not be {@code null}
     * @throws eu.exeris.kernel.spi.exceptions.flow.FlowRegistryException if the transition
     *         references an unknown step id
     * @throws IllegalStateException if called after {@link FlowEngine#start()}
     */
    void registerTransition(FlowTransitionDescriptor transition);

    /**
     * Looks up a registered step by its id.
     *
     * <p>Must execute in <b>O(1)</b> time. Never allocates on the hot path.
     *
     * @param stepId the step identifier
     * @return the registered descriptor; never {@code null}
     * @throws eu.exeris.kernel.spi.exceptions.flow.FlowRegistryException if no step
     *         with the given id is registered
     */
    FlowStepDescriptor lookupStep(int stepId);

    /**
     * Returns all outgoing transitions from the given step.
     *
     * <p>Must execute in <b>O(1)</b> time. Returns an empty array (not {@code null})
     * if no transitions are registered for this step.
     *
     * <p><b>Read-only contract:</b> the returned array is owned by the registry.
     * Callers MUST NOT modify its contents. Implementations MAY return a direct
     * reference to an internal shared array to avoid hot-path allocations; any
     * mutation by a caller would silently corrupt the registry's internal state.
     * If the caller needs a mutable copy it must perform its own {@code Arrays.copyOf}.
     *
     * @param fromStep the source step id
     * @return array of outgoing transitions; never {@code null}, may be empty; treat as immutable
     */
    FlowTransitionDescriptor[] lookupTransitions(int fromStep);
}

