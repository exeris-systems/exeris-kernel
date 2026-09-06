/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.core.flow;

import jdk.jfr.Category;
import jdk.jfr.Description;
import jdk.jfr.Event;
import jdk.jfr.Label;
import jdk.jfr.Name;
import jdk.jfr.StackTrace;

/**
 * Emitted when a step's action or its compensation throws during execution, carrying the
 * exception's class and message but never its stack trace.
 *
 * <p>The same event covers both call sites — a failing forward step and a failing compensation —
 * distinguished, if at all, only by whichever step index and definition the caller supplies; the
 * event itself carries no field naming which of the two produced it.
 */
@Name("eu.exeris.kernel.flow.StepFailed")
@Label("Flow Step Failed")
@Category({"Exeris Kernel", "Flow"})
@Description("Emitted when a flow step throws during execution. instanceIdMost/Least identify the saga instance.")
@StackTrace(false)
final class FlowStepFailedEvent extends Event {

    @Label("Definition Name")
    @Description("Flow plan definition name")
    /* default */ String definitionName;

    @Label("Step Index")
    @Description("Zero-based index of the step that failed")
    /* default */ int stepIndex;

    @Label("Instance ID (most)")
    @Description("Most significant bits of the flow instance key UUID")
    /* default */ long instanceIdMost;

    @Label("Instance ID (least)")
    @Description("Least significant bits of the flow instance key UUID")
    /* default */ long instanceIdLeast;

    @Label("Failure Reason")
    @Description("Exception class name and message — no secrets, no stack trace")
    /* default */ String failureReason;

    /**
     * Emits the {@code StepFailed} event recording which step threw and why, or does nothing if
     * the event type is disabled.
     *
     * @param definitionName  flow plan definition name
     * @param stepIndex       zero-based index of the step that failed
     * @param instanceIdMost  most-significant bits of the flow instance key UUID
     * @param instanceIdLeast least-significant bits of the flow instance key UUID
     * @param cause           the failure the step action or its compensation threw; recorded as
     *                        class name and message only, never a stack trace
     */
    /* default */ static void emit(String definitionName, int stepIndex,
                                   long instanceIdMost, long instanceIdLeast,
                                   Throwable cause) {
        FlowStepFailedEvent event = new FlowStepFailedEvent();
        if (!event.isEnabled()) {
            return;
        }
        event.definitionName = definitionName;
        event.stepIndex = stepIndex;
        event.instanceIdMost = instanceIdMost;
        event.instanceIdLeast = instanceIdLeast;
        event.failureReason = cause.getClass().getName() + ": " + cause.getMessage();
        event.commit();
    }
}
