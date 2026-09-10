/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.core.flow;

import eu.exeris.kernel.spi.flow.model.FlowExecutionPlan;
import eu.exeris.kernel.spi.flow.model.FlowStepDescriptor;
import eu.exeris.kernel.spi.flow.model.FlowTransitionDescriptor;

import java.util.Arrays;

/**
 * Heap-backed {@link FlowExecutionPlan}: an immutable snapshot of one compiled
 * {@link eu.exeris.kernel.spi.flow.model.FlowDefinition}'s steps, transition adjacency and
 * precomputed next-step index, produced by {@link CoreFlowPlanFactory#compile}.
 *
 * <p>The constructor copies the arrays it is given via {@code Arrays.copyOf} — a shallow copy for
 * the two-dimensional {@code transitions} table — so the plan does not alias the caller's own array
 * references once construction returns.
 *
 * <p><b>Allocation:</b> allocates nothing beyond the three arrays copied at construction;
 * {@link #stepAt} and {@link #nextStep} are plain array reads.
 * <p><b>Thread confinement:</b> any thread — the plan is immutable once constructed and is shared,
 * unsynchronized, across every flow instance running against it.
 * <p><b>Ownership:</b> owned by {@link CoreFlowPlanFactory}'s plan catalogue; a caller holds a
 * borrowed reference and releases nothing.
 */
final class CoreFlowExecutionPlan implements FlowExecutionPlan {

    private final String definitionName;
    private final int definitionVersion;
    private final FlowStepDescriptor[] steps;
    private final FlowTransitionDescriptor[][] transitions;
    private final int[] nextSteps;
    private final long timeoutDurationNanos;

    /* default */
    CoreFlowExecutionPlan(String definitionName,
                          int definitionVersion,
                          FlowStepDescriptor[] steps,
                          FlowTransitionDescriptor[][] transitions,
                          int[] nextSteps,
                          long timeoutDurationNanos) {
        this.definitionName = definitionName;
        this.definitionVersion = definitionVersion;
        this.steps = Arrays.copyOf(steps, steps.length);
        this.transitions = Arrays.copyOf(transitions, transitions.length);
        this.nextSteps = Arrays.copyOf(nextSteps, nextSteps.length);
        this.timeoutDurationNanos = timeoutDurationNanos;
    }

    @Override
    public String definitionName() {
        return definitionName;
    }

    @Override
    public int definitionVersion() {
        return definitionVersion;
    }

    @Override
    public int stepCount() {
        return steps.length;
    }

    @Override
    public FlowStepDescriptor stepAt(int stepIndex) {
        return steps[stepIndex];
    }

    @Override
    public long timeoutDurationNanos() {
        return timeoutDurationNanos;
    }

    /**
     * Returns the outgoing transitions {@link CoreFlowPlanFactory#compile} recorded for the step at
     * {@code stepIndex}.
     *
     * @param stepIndex the 0-based step index; not bounds-checked below zero
     * @return the step's outgoing transitions, in declaration order; never {@code null}, and an
     *         empty array both when the step declares none and when {@code stepIndex} is at or past
     *         {@link #stepCount()}
     */
    /* default */
    FlowTransitionDescriptor[] transitionsAt(int stepIndex) {
        return stepIndex < transitions.length ? transitions[stepIndex] : new FlowTransitionDescriptor[0];
    }

    /**
     * Returns the precomputed step index {@link CoreFlowRuntime} advances to after {@code stepIndex}
     * completes with {@link eu.exeris.kernel.spi.flow.model.FlowOutcome#CONTINUE}.
     *
     * @param stepIndex the 0-based step index
     * @return the next step's index; {@code -1} both when {@code stepIndex} is outside
     *         {@code [0, }{@link #stepCount()}{@code )} and when the step at {@code stepIndex} is the
     *         last one in the plan and declares no outgoing transition — the two cases are not
     *         distinguished by this return value
     */
    /* default */
    int nextStep(int stepIndex) {
        if (stepIndex < 0 || stepIndex >= nextSteps.length) {
            return -1;
        }
        return nextSteps[stepIndex];
    }
}