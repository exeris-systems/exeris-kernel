/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.spi.flow.model;

/**
 * A compiled, ready-to-execute flow plan produced by
 * {@link eu.exeris.kernel.spi.flow.FlowExecutionPlanFactory#compile(FlowDefinition)}.
 *
 * <p>A plan is immutable once compiled, and it is what a running instance resolves its steps
 * against: the pair {@link #definitionName()} and {@link #definitionVersion()} is the key a parked
 * saga must still match when it resumes (ADR-064).
 *
 * <p><b>Allocation:</b> allocates ({@link #stepAt} only — a binding that holds its steps off-heap
 * materialises a descriptor per call). {@link #definitionName()}, {@link #definitionVersion()},
 * {@link #stepCount()} and {@link #timeoutDurationNanos()} are field reads and allocate nothing.
 * <p><b>Thread confinement:</b> any thread — a plan is immutable after compilation, and one instance
 * is scheduled concurrently for as many flow instances as are running against it.
 * <p><b>Ownership:</b> the engine's plan catalogue owns every compiled plan; a caller holds a
 * borrowed reference and releases nothing. The catalogue reclaims no entry on its own — retiring a
 * version is an operator action, and {@code FlowEngineConfig.maxExecutionPlans} bounds how many
 * name-and-version pairs may be retained at once.
 *
 * @implNote The Community binding is a thin heap wrapper over the compiled {@link FlowDefinition}
 *           and its step list, collected when nothing references it. The Enterprise binding holds
 *           raw off-heap base addresses ({@code long}) into the step-registry and
 *           transition-adjacency slabs, so the plan carries no heap reference on the hot path.
 * @since 0.5
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
     * whose definition declares no version: it is the only version of that definition, and a snapshot
     * written by it carries no version to disagree with. The default exists because an interface with
     * out-of-tree implementations cannot grow an abstract method without breaking every one of them
     * at invoke time.
     *
     * @return the declared version, {@code >=} {@link FlowDefinition#INITIAL_VERSION}
     * @implSpec An implementation whose compiled definition carries any version other than
     *           {@link FlowDefinition#INITIAL_VERSION} MUST override this. Inheriting the default
     *           fails quietly rather than loudly: such a plan registers and writes its snapshots as
     *           version 1, and a genuine version-1 plan will then resume them, because the resume
     *           check compares this value against the snapshot's and the two agree.
     *           {@code AbstractFlowDefinitionVersioningTck} pins the returned version to the compiled
     *           definition's, so a provider that runs the TCK is covered and one that does not is
     *           not.
     * @since 0.11
     */
    default int definitionVersion() {
        return FlowDefinition.INITIAL_VERSION;
    }

    /**
     * How many steps this plan holds — the exclusive upper bound for {@link #stepAt(int)}, and the
     * arity a resuming saga's persisted cursor is checked against before any step replays.
     *
     * @return the step count; always &gt;= 1, since a definition must declare at least one step
     */
    int stepCount();

    /**
     * Resolves one step of this plan by position, so a caller can read its identity, its action and
     * its compensation.
     *
     * @param stepIndex zero-based step index
     * @return the descriptor at that position; never {@code null}
     * @throws IndexOutOfBoundsException if {@code stepIndex} is negative or {@code >= stepCount()}
     * @implSpec MUST resolve in <b>O(1)</b> time.
     * @apiNote Cold and diagnostic path. A dispatcher MUST NOT call this once per step — it may
     *          allocate — and should drive execution from whatever representation the plan holds
     *          internally, leaving {@code stepAt} to JFR payloads, diagnostics, resume validation and
     *          TCK verification.
     * @implNote {@link FlowStepDescriptor} carries heap references (a {@code String} name and two
     *           {@link FlowStepAction}s) that survive even where the slab underneath stores raw
     *           addresses and ordinals. An Enterprise binding therefore materialises a descriptor on
     *           demand, reading the slab at
     *           {@code stepSlabBase + stepIndex * STEP_DESCRIPTOR_STRIDE}, at the cost of one heap
     *           allocation per call.
     */
    FlowStepDescriptor stepAt(int stepIndex);

    /**
     * How long an instance compiled from this plan may run before the engine times it out and drives
     * it through compensation.
     *
     * <p>This is a <strong>duration</strong>, not an absolute deadline. When a new flow
     * instance is created from this plan, the scheduler computes the absolute deadline as:
     * {@snippet lang="java" :
     * long deadlineNanos = System.nanoTime() + plan.timeoutDurationNanos();
     * }
     * and stores it in {@link FlowContext#timeoutNanos()} (which IS an absolute deadline
     * in the {@code System.nanoTime()} epoch).
     *
     * @return configured duration limit in nanoseconds; always &gt; 0
     */
    long timeoutDurationNanos();
}

