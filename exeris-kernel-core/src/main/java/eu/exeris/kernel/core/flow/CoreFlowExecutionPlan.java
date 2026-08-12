/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.core.flow;

import eu.exeris.kernel.spi.flow.model.FlowExecutionPlan;
import eu.exeris.kernel.spi.flow.model.FlowStepDescriptor;
import eu.exeris.kernel.spi.flow.model.FlowTransitionDescriptor;

import java.util.Arrays;

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

    /* default */
    FlowTransitionDescriptor[] transitionsAt(int stepIndex) {
        return stepIndex < transitions.length ? transitions[stepIndex] : new FlowTransitionDescriptor[0];
    }

    /* default */
    int nextStep(int stepIndex) {
        if (stepIndex < 0 || stepIndex >= nextSteps.length) {
            return -1;
        }
        return nextSteps[stepIndex];
    }
}