/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.spi.flow.model;

/**
 * Functional interface for a single executable step within a flow.
 *
 * <h2>Execution Contract</h2>
 * <p>Receives a {@link FlowContext} view of the current flow instance state and
 * returns a {@link FlowOutcome} signalling what the scheduler should do next.
 *
 * <h2>Context Access</h2>
 * <p>Additional cross-cutting data (memory allocator, telemetry sinks, persistence engine)
 * is obtained via {@link eu.exeris.kernel.spi.context.KernelProviders} scoped slots —
 * NOT via method parameters. This keeps the SPI signature minimal and prevents
 * "parameter pollution" anti-patterns.
 *
 * <h2>Zero-Allocation Contract (Enterprise)</h2>
 * <p>Implementations running in the Enterprise tier MUST NOT allocate on the heap
 * during {@link #execute(FlowContext)}. All transient buffers must be acquired from
 * {@link eu.exeris.kernel.spi.memory.MemoryAllocator} via {@code try-with-resources}.
 *
 * @since 0.5
 * @see FlowContext
 * @see FlowOutcome
 */
@FunctionalInterface
public interface FlowStepAction {

    /**
     * Executes this step for the given flow context.
     *
     * @param context the current flow instance context; never {@code null}
     * @return the outcome signalling the next scheduler action; never {@code null}
     */
    FlowOutcome execute(FlowContext context);
}

