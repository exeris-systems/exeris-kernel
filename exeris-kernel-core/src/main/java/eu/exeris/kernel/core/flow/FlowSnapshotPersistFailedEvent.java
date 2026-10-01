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
 * Emitted every time {@link eu.exeris.kernel.spi.flow.model.FlowSnapshotStore#save} throws while
 * checkpointing an instance, immediately before {@link FlowSnapshotWriter} rethrows the failure to
 * its caller.
 *
 * <p>The in-memory transition the snapshot was meant to make durable has already been applied when
 * this fires, so the failure leaves the instance running on a state the durable store never
 * accepted. The PARK path retries the write and, past its retry budget, marks the instance
 * non-durable instead of propagating; every other caller lets the rethrown failure escape
 * uncaught. Either way, this event is the only record of which write failed and why.
 */
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

    /**
     * Emits the {@code SnapshotPersistFailed} event recording which checkpoint write failed and
     * why, or does nothing if the event type is disabled.
     *
     * @param definitionName  flow plan definition name
     * @param state           state the refused snapshot would have recorded
     * @param stepIndex       zero-based index of the step the snapshot would have recorded
     * @param instanceIdMost  most-significant bits of the flow instance key UUID
     * @param instanceIdLeast least-significant bits of the flow instance key UUID
     * @param cause           the failure the store's {@code save} threw; recorded as class name
     *                        and message only, never a stack trace
     */
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
