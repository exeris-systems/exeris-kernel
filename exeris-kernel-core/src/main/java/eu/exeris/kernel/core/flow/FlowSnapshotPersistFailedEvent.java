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

@Name("eu.exeris.kernel.flow.SnapshotPersistFailed")
@Label("Flow Snapshot Persist Failed")
@Category({"Exeris Kernel", "Flow"})
@Description("Emitted when FlowSnapshotStore.save() throws while checkpointing an instance. "
        + "The write does not roll back the in-memory transition that preceded it, so the "
        + "instance keeps running on a state the durable store never accepted; on PARK that "
        + "means a saga this JVM can still wake but a restart cannot recover. Without this "
        + "event the only trace is the uncaught exception on the flow virtual thread.")
@StackTrace(false)
final class FlowSnapshotPersistFailedEvent extends Event {

    @Label("Definition Name")
    @Description("Flow plan definition name")
    /* default */ String definitionName;

    @Label("Target State")
    @Description("State the refused snapshot would have recorded (e.g. PARKED, COMPENSATING)")
    /* default */ String state;

    @Label("Step Index")
    @Description("Zero-based index of the step the snapshot would have recorded")
    /* default */ int stepIndex;

    @Label("Instance ID (most)")
    @Description("Most significant bits of the flow instance key UUID")
    /* default */ long instanceIdMost;

    @Label("Instance ID (least)")
    @Description("Least significant bits of the flow instance key UUID")
    /* default */ long instanceIdLeast;

    @Label("Failure Reason")
    @Description("Exception class name and message - no secrets, no stack trace")
    /* default */ String failureReason;

    /* default */ static void emit(String definitionName, String state, int stepIndex,
                                   long instanceIdMost, long instanceIdLeast,
                                   Throwable cause) {
        FlowSnapshotPersistFailedEvent event = new FlowSnapshotPersistFailedEvent();
        if (!event.isEnabled()) {
            return;
        }
        event.definitionName = definitionName;
        event.state = state;
        event.stepIndex = stepIndex;
        event.instanceIdMost = instanceIdMost;
        event.instanceIdLeast = instanceIdLeast;
        event.failureReason = cause.getClass().getName() + ": " + cause.getMessage();
        event.commit();
    }
}
