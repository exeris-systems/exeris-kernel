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
 */
@Label("Outbox Loop Failure")
@Category({"Exeris", "Events", "Outbox"})
@StackTrace(false)
public final class OutboxLoopFailureEvent extends Event {

    @Label("Exception Type")
    public String exceptionType;

    @Label("State At Failure")
    public String stateAtFailure;
}
