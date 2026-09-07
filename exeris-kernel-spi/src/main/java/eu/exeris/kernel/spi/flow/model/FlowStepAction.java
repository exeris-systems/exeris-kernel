/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.spi.flow.model;

/**
 * One unit of application work inside a flow — the body of a saga step, and of the compensation
 * that undoes it.
 *
 * <p>An action reads the {@link FlowContext} view of the instance it is running for and returns a
 * {@link FlowOutcome} that tells the scheduler what to do next: continue, short-circuit to
 * completion, park until an external event arrives, or fail into compensation.
 *
 * <p><b>Allocation:</b> zero-alloc on hot path — {@link #execute(FlowContext)} runs on the dispatch
 * path, and an Enterprise-tier implementation is required to allocate nothing on the heap while it
 * runs.
 * <p><b>Ownership:</b> an action owns every buffer it acquires for the duration of the call and
 * releases it before returning; a buffer that outlives {@code execute} is a leak the engine cannot
 * see.
 *
 * @implSpec An implementation running in the Enterprise tier MUST NOT allocate on the heap during
 *           {@link #execute(FlowContext)}. Transient buffers MUST be acquired from
 *           {@link eu.exeris.kernel.spi.memory.MemoryAllocator} and released with
 *           {@code try-with-resources}.
 * @apiNote Cross-cutting collaborators — the memory allocator, telemetry sinks, the persistence
 *          engine — are read from {@link eu.exeris.kernel.spi.context.KernelProviders} scoped slots
 *          rather than passed in. That is what keeps this signature to one parameter instead of
 *          growing one per collaborator.
 * @since 0.5
 * @see FlowContext
 * @see FlowOutcome
 */
@FunctionalInterface
public interface FlowStepAction {

    /**
     * Performs this step's work for one flow instance and decides, by its return value, where that
     * instance goes next.
     *
     * @param context the instance this invocation is running for; never {@code null}
     * @return the outcome signalling the next scheduler action; never {@code null}
     * @apiNote Returning {@link FlowOutcome#FAIL} and throwing both route the instance into
     *          compensation; returning is the controlled form and keeps the failure reason in the
     *          engine's hands.
     */
    FlowOutcome execute(FlowContext context);
}

