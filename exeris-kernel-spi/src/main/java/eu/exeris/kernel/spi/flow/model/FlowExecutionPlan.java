/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
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
     * The version of the {@link FlowDefinition} this plan was compiled from.
     *
     * <p>Together with {@link #definitionName()} this is the key a parked saga resumes on: several
     * versions of one definition may be registered at once, and a snapshot binds to the exact one it
     * parked under rather than to whichever is newest (ADR-064).
     *
     * <p>Defaults to {@link FlowDefinition#INITIAL_VERSION}, which is the honest answer for a plan
     * compiled before versioning existed: it was the only version of its definition, and a snapshot
     * written by it carries no version to disagree with. The default is what keeps this addition off
     * the implementor's critical path — the sibling
     * {@link eu.exeris.kernel.spi.flow.FlowExecutionPlanFactory#registerMigration} added in the same
     * milestone has one for the same reason, and an interface this old cannot grow an abstract method
     * without breaking every out-of-tree implementation of it at invoke time.
     *
     * <p><b>The default trades a loud failure for a quiet one, so the TCK is now what enforces
     * this.</b> Before, a plan that did not implement this did not compile. Now a v3 plan that
     * forgets to override registers and writes its snapshots as version 1, and a genuine v1 plan
     * will resume them — the resume check compares this value against the snapshot's, and they
     * agree. {@code AbstractFlowDefinitionVersioningTck} pins the returned version to the compiled
     * definition's, so a provider that runs the TCK is covered; one that does not is on its own in
     * a way the compiler used to prevent.
     *
     * @return the declared version, {@code >= FlowDefinition.INITIAL_VERSION}
     * @since 0.11.0
     */
    default int definitionVersion() {
        return FlowDefinition.INITIAL_VERSION;
    }

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
     *
     * <p><b>Cold / diagnostic path only.</b> {@link FlowStepDescriptor} carries heap
     * references ({@code String name}, {@code FlowStepAction}) that cannot be eliminated
     * even in the Enterprise tier, where the underlying slab stores raw addresses and
     * ordinals. Enterprise implementations materialise a {@code FlowStepDescriptor}
     * on demand by reading the slab at
     * {@code stepSlabBase + stepIndex * STEP_DESCRIPTOR_STRIDE}, which incurs a heap
     * allocation per call.
     *
     * <p>The hot-path scheduler MUST NOT call this method per step. Use the raw slab
     * addresses stored in the plan for dispatch; reserve {@code stepAt()} for JFR
     * events, diagnostics, and TCK verification.
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

