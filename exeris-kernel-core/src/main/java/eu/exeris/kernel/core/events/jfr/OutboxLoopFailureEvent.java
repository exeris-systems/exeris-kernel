/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.core.events.jfr;

import jdk.jfr.Category;
import jdk.jfr.Event;
import jdk.jfr.Label;
import jdk.jfr.StackTrace;

/**
 * JFR event emitted when the outbox poll-flush loop terminates by throwing.
 *
 * <p>The tick body already absorbs a {@link RuntimeException} from the store or the broker and backs
 * off, so reaching this event means something the loop was never going to recover from — typically an
 * {@link Error}. The loop is gone, and without this the orchestrator would keep reporting a running
 * state machine while nothing polled: a stall no health check can see, with unpublished events
 * accumulating behind it.
 *
 * <p>Carries the exception's <em>type</em> only. An outbox failure is raised while handling
 * application events, and a message can carry their contents.
 *
 * <p>Emitted by the orchestrator's owner virtual thread, from within {@code ownerLoop()}, right
 * after the forked poll-flush task's {@code StructuredScope} join reports it as failed.
 */
@Label("Outbox Loop Failure")
@Category({"Exeris", "Events", "Outbox"})
@StackTrace(false)
public final class OutboxLoopFailureEvent extends Event {

    /** Fully-qualified class name of the {@link Throwable} that escaped the poll-flush loop. */
    @Label("Exception Type")
    public String exceptionType;

    /**
     * The state-machine state name (one of {@code IDLE}, {@code POLLING}, {@code FLUSHING},
     * {@code WAITING}, {@code RETRYING}) the orchestrator was in at the moment the loop failed —
     * captured before this failure forces the state machine to {@code STOPPED}.
     */
    @Label("State At Failure")
    public String stateAtFailure;
/**
 * Creates an unrecorded event.
 *
 * <p>The emitter assigns the public fields and calls {@link Event#commit()}. An instance that is never
 * committed contributes nothing to a recording.
 */
public OutboxLoopFailureEvent() {
    // Declared, not added: the implicit no-arg constructor, written out so it can carry a comment.
    super();
}

}
