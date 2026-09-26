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
 * Emitted when {@link CoreFlowRuntime} cannot re-launch a wake that arrived for an instance still
 * running its current step.
 *
 * <p>The instance is put back into {@code PARKED} with the wake re-armed before this fires, so it
 * stays recoverable from a later wake; without this event, the abandoned re-submission would be
 * indistinguishable from a saga that simply had no more work to do.
 *
 * <p>Carries the failure's exception type only — a saga's failure message can hold the business
 * payload the step was processing.
 */
@Name("eu.exeris.kernel.flow.DeferredWakeFailed")
@Label("Flow Deferred Wake Failed")
@Category({"Exeris Kernel", "Flow"})
@Description("Emitted when a wake deferred past a running step could not be re-submitted. The "
        + "instance is returned to PARKED with the wake re-armed, so the next wake resumes it; "
        + "without this event, a re-submission that never started would look like a saga that "
        + "simply had nothing more to do.")
@StackTrace(false)
final class FlowDeferredWakeFailedEvent extends Event {

    @Label("Definition Name")
    @Description("Flow plan definition name")
    /* default */ String definitionName;

    @Label("Exception Type")
    @Description("Class name of the failure. Type only — a saga's failure message can carry the "
            + "business payload it was processing.")
    /* default */ String exceptionType;
}
