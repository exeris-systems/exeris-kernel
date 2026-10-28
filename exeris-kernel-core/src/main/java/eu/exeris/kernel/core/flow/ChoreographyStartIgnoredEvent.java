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
 * JFR event emitted when a choreography {@code Start} names an instance the engine already holds,
 * so the request was absorbed and no flow was started.
 *
 * <p>A redelivered event is normal delivery behaviour and the no-op is correct, so this event is
 * not an error. It exists for the other case: a mapper that gives distinct business triggers the
 * same instance id would otherwise lose the second saga without any trace. Emitted only from the
 * choreography path; a plain schedule that resubmits a parked instance is not reported here.
 *
 * <p>Single-phase: the event is committed once, with no begin and no blocking call in between, since
 * it fires on a virtual thread the event bus dispatches.
 *
 * @since 0.13
 */
@Name("eu.exeris.kernel.flow.ChoreographyStartIgnored")
@Label("Choreography Start Ignored")
@Category({"Exeris Kernel", "Flow"})
@Description("Emitted when a choreography Start names an instance the engine already holds "
        + "(running, parked or terminal). The Start is a no-op and the supplied plan is ignored. "
        + "The existingState field records the FlowState the engine found.")
@StackTrace(false)
final class ChoreographyStartIgnoredEvent extends Event {

    @Label("Engine Name")
    @Description("Human-readable engine name from FlowEngineConfig.engineName()")
    /* default */ String engineName;

    @Label("Instance Id (most)")
    @Description("Most-significant 64 bits of the flow instance UUID")
    /* default */ long instanceIdMost;

    @Label("Instance Id (least)")
    @Description("Least-significant 64 bits of the flow instance UUID")
    /* default */ long instanceIdLeast;

    @Label("Existing State")
    @Description("State of the instance the engine found: RUNNING, PARKED, COMPLETED or "
            + "FAILED_ROLLEDBACK; TERMINAL when the instance finished but its terminal state "
            + "is no longer cached")
    /* default */ String existingState;

    /**
     * Commits the event, or does nothing when this event type is disabled.
     *
     * @param engineName      human-readable engine name, from {@code FlowEngineConfig.engineName()}
     * @param instanceIdMost  most-significant bits of the instance UUID the Start named
     * @param instanceIdLeast least-significant bits of the instance UUID the Start named
     * @param existingState   the {@code FlowState} name the engine found for that instance, or {@code TERMINAL}
     *                        when only the fact that it finished is known
     */
    /* default */ static void emit(String engineName, long instanceIdMost, long instanceIdLeast,
                                   String existingState) {
        ChoreographyStartIgnoredEvent event = new ChoreographyStartIgnoredEvent();
        if (!event.isEnabled()) {
            return;
        }
        event.engineName = engineName;
        event.instanceIdMost = instanceIdMost;
        event.instanceIdLeast = instanceIdLeast;
        event.existingState = existingState;
        event.commit();
    }
}
