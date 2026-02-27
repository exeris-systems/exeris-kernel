/*
 * Copyright (C) 2025-2026 Exeris. All rights reserved.
 *
 * This code is part of the Exeris Systems.
 * Distributed under the proprietary Exeris Software License.
 * Unauthorized copying or distribution is prohibited.
 */
package eu.exeris.kernel.spi.flow.model;

/**
 * A compiled, ready-to-execute flow plan produced by
 * {@link eu.exeris.kernel.spi.flow.FlowExecutionPlanFactory#compile(FlowDefinition)}.
 *
 * <h2>Tier Behaviour</h2>
 * <ul>
 *   <li><b>Community</b>: a thin heap wrapper holding a reference to the compiled
 *       {@link FlowDefinition} and its step list. Garbage-collected when no longer
 *       referenced.</li>
 *   <li><b>Enterprise</b>: a thin wrapper holding raw off-heap base addresses
 *       ({@code long}) pointing into the step registry and transition adjacency slabs.
 *       The plan itself contains no heap references on the hot path — only raw pointers.</li>
 * </ul>
 *
 * <h2>Immutability</h2>
 * <p>A {@code FlowExecutionPlan} is immutable after creation. The same plan instance
 * can be safely scheduled for multiple concurrent flow instances.
 *
 * @since 0.5.0
 * @see FlowDefinition
 * @see eu.exeris.kernel.spi.flow.FlowExecutionPlanFactory
 */
public interface FlowExecutionPlan {

    /**
     * The name of the {@link FlowDefinition} this plan was compiled from.
     *
     * @return non-null, non-blank definition name
     */
    String definitionName();

    /**
     * The total number of steps in this plan.
     *
     * @return step count ≥ 1
     */
    int stepCount();

    /**
     * Returns the {@link FlowStepDescriptor} for the given step index.
     *
     * <p>Must execute in <b>O(1)</b> time.
     * Enterprise: direct slab address arithmetic (no heap lookup).
     *
     * @param stepIndex zero-based step index
     * @return step descriptor; never {@code null}
     * @throws IndexOutOfBoundsException if {@code stepIndex >= stepCount()}
     */
    FlowStepDescriptor stepAt(int stepIndex);

    /**
     * Returns the configured flow <em>duration</em> limit in nanoseconds.
     *
     * <p>This is a <strong>duration</strong>, not an absolute deadline. When a new flow
     * instance is created from this plan, the scheduler computes the absolute deadline as:
     * <pre>{@code
     *   long deadlineNanos = System.nanoTime() + plan.timeoutDurationNanos();
     * }</pre>
     * and stores it in {@link FlowContext#timeoutNanos()} (which IS an absolute deadline
     * in the {@code System.nanoTime()} epoch).
     *
     * @return configured duration limit in nanoseconds; always &gt; 0
     */
    long timeoutDurationNanos();
}

