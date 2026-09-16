/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.spi.flow.model;

/**
 * The outcome of a single flow step execution.
 *
 * <p>Returned by {@link FlowStepAction#execute(FlowContext)} to signal the scheduler
 * what to do next with this flow instance.
 *
 * @since 0.5
 * @see FlowStepAction
 */
public enum FlowOutcome {

    /**
     * Step succeeded — continue to the next step.
     * The scheduler advances {@code currentStep} and re-enqueues the flow context.
     */
    CONTINUE,

    /**
     * Step succeeded and ends the whole flow here — a short circuit.
     * The scheduler transitions the instance straight to {@link FlowState#COMPLETED}
     * without executing any remaining step.
     */
    COMPLETE,

    /**
     * Step is waiting for an external event — suspend execution.
     * The scheduler calls {@link eu.exeris.kernel.spi.flow.FlowScheduler#park(FlowContext)}.
     * Execution resumes when {@link eu.exeris.kernel.spi.flow.FlowScheduler#wake(FlowContext)}
     * is called.
     */
    PARK,

    /**
     * Step failed — trigger backward compensation.
     * The scheduler sets state to {@link FlowState#COMPENSATING} and executes
     * compensation steps in reverse order.
     */
    FAIL
}

