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
 * JFR event emitted when the Outbox Orchestrator transitions between states.
 *
 * <p>Emitted by the orchestrator's internal state machine on every successful CAS transition —
 * both the ordinary {@code transitionTo} path driven by the poll-flush tick loop and the forced
 * transition to {@code STOPPED} that {@code stop()} and loop-failure handling trigger.
 *
 * @since 0.5
 */
@Label("Outbox State Transition")
@Category({"Exeris", "Events", "Outbox"})
@StackTrace(false)
public final class OutboxStateTransitionEvent extends Event {

    /**
     * State-machine state name before the transition: one of {@code IDLE}, {@code POLLING},
     * {@code FLUSHING}, {@code WAITING}, {@code RETRYING}, {@code STOPPED}.
     */
    @Label("Previous State")
    public String previousState;

    /** State-machine state name after the transition, from the same six-state vocabulary. */
    @Label("Next State")
    public String nextState;

    /**
     * Number of pending events polled from the store this tick. Non-zero only on the transition
     * into {@code FLUSHING}, where it is the size of the batch about to be flushed; every other
     * transition, including the forced one to {@code STOPPED}, reports zero here.
     */
    @Label("Polled Event Count")
    public int polledCount;
/**
 * Creates an unrecorded event.
 *
 * <p>The emitter assigns the public fields and calls {@link Event#commit()}. An instance that is never
 * committed contributes nothing to a recording.
 */
public OutboxStateTransitionEvent() {
    // Declared, not added: the implicit no-arg constructor, written out so it can carry a comment.
    super();
}

}
