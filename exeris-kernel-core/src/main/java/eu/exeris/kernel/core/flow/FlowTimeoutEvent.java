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

@Name("eu.exeris.kernel.flow.Timeout")
@Label("Flow Saga Timeout")
@Category({"Exeris Kernel", "Flow"})
@Description("Emitted when a flow instance is detected past its absolute timeout deadline;" + 
    " the engine drives compensation.")
@StackTrace(false)
final class FlowTimeoutEvent extends Event {

    @Label("Engine Name")
    @Description("Human-readable engine name from FlowEngineConfig.engineName()")
    /* default */ String engineName;

    @Label("Definition Name")
    @Description("FlowDefinition name of the timed-out instance")
    /* default */ String definitionName;

    @Label("Instance Id (most)")
    @Description("Most-significant 64 bits of the flow instance UUID")
    /* default */ long instanceIdMost;

    @Label("Instance Id (least)")
    @Description("Least-significant 64 bits of the flow instance UUID")
    /* default */ long instanceIdLeast;

    @Label("Current Step")
    @Description("Step index the instance was attempting to execute when the timeout was detected")
    /* default */ int currentStep;

    @Label("Timeout Overrun (ns)")
    @Description("Nanoseconds elapsed past the deadline at the moment of detection (>= 0)")
    /* default */ long overrunNanos;

    /* default */ static void emit(
            String engineName,
            String definitionName,
            long instanceIdMost,
            long instanceIdLeast,
            int currentStep,
            long overrunNanos) {
        FlowTimeoutEvent event = new FlowTimeoutEvent();
        if (!event.isEnabled()) {
            return;
        }
        event.begin();
        event.engineName = engineName;
        event.definitionName = definitionName;
        event.instanceIdMost = instanceIdMost;
        event.instanceIdLeast = instanceIdLeast;
        event.currentStep = currentStep;
        event.overrunNanos = overrunNanos;
        event.commit();
    }
}
