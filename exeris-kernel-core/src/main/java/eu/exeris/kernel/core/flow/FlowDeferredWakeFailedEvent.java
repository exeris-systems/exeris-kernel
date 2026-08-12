/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.core.flow;

import jdk.jfr.Category;
import jdk.jfr.Description;
import jdk.jfr.Event;
import jdk.jfr.Label;
import jdk.jfr.Name;
import jdk.jfr.StackTrace;

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
