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
