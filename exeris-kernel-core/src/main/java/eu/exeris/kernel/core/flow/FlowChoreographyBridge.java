/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.core.flow;

import eu.exeris.kernel.spi.events.EventDescriptor;
import eu.exeris.kernel.spi.events.EventHandler;
import eu.exeris.kernel.spi.events.EventPayload;
import eu.exeris.kernel.spi.exceptions.flow.FlowEngineException;
import eu.exeris.kernel.spi.flow.ChoreographyDecision;
import eu.exeris.kernel.spi.flow.FlowChoreographyMapper;
import eu.exeris.kernel.spi.flow.FlowScheduler;
import eu.exeris.kernel.spi.flow.model.FlowContext;
import eu.exeris.kernel.spi.flow.model.FlowExecutionPlan;
import eu.exeris.kernel.spi.flow.model.FlowState;

import java.util.Objects;

/**
 * Core bridge between the Events SPI and the Flow SPI for choreography-driven sagas.
 *
 * <p>On each event, closes the payload immediately (payload bytes are not needed for
 * routing), maps the descriptor via the supplied {@link FlowChoreographyMapper}, and
 * dispatches the resulting {@link ChoreographyDecision} to the {@link FlowScheduler}.
 *
 * <h2>RAII Contract</h2>
 * <p>The {@code payload} is closed in all branches, including {@code Ignore} and
 * exception paths, via the try-with-resources block.
 *
 * @since 0.5.0
 */
final class FlowChoreographyBridge implements EventHandler {

    private final FlowChoreographyMapper mapper;
    private final FlowScheduler scheduler;

    /* default */ FlowChoreographyBridge(FlowChoreographyMapper mapper, FlowScheduler scheduler) {
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
    }

    @Override
    public void handle(EventDescriptor descriptor, EventPayload payload) {
        try (payload) {
            ChoreographyDecision decision = mapper.map(descriptor);
            switch (decision) {
                case ChoreographyDecision.Wake(long most, long least) -> {
                    // Present at lookup and gone by the time wake lands: the same event, losing a
                    // race it cannot win. lookupParked-then-wake is check-then-act and cannot be
                    // made atomic from out here, so the engine's refusal is the expected outcome,
                    // not a fault — the choreographed instance is running, which is what the event
                    // asked for. Discriminated by the NOT_PARKED reason rather than message text;
                    // every other EX-FLOW-7002 still propagates.
                    //
                    // Absent at lookup is not, on its own, a stale event, which is why the empty
                    // branch below still delivers the wake instead of dropping it. lookupParked
                    // reports a live, running instance as absent deliberately: handing it out as
                    // parked would let a second event schedule the same flow again (pinned by
                    // AbstractFlowSchedulerTck.deferredWakeAfterSelfParkClearsParkedRegistration).
                    // But a callback can land while the instance is still inside the step that is
                    // about to PARK, and dropping it there strands the saga for good, since one
                    // event per business trigger means nothing re-sends it. wake() with parked
                    // intent resolves that instance and defers it through wakePending; a genuinely
                    // stale key still fails NOT_PARKED and is swallowed exactly as before.
                    // Cost, measured against the alternative rather than assumed away: for a key
                    // the engine has never heard of, this pays a second snapshot-store read.
                    // lookupParked already probed the store and recorded the miss, but wake()
                    // clears that negative entry on entry, so loadSnapshot's suppression does
                    // not catch the second probe. Both obvious repairs are worse: not clearing
                    // it would make a stale negative outlive a park that happened on another
                    // engine, refusing a legitimate cross-engine wake - the loss this fix
                    // exists to remove; and skipping the delivery when lookupParked came back
                    // empty is the original bug. terminalStateCatalog short-circuits known
                    // terminal keys before either read, so the doubled I/O is confined to
                    // genuinely unknown or evicted keys. Threading the probe result through
                    // needs SPI surface and is tracked as follow-up work.
                    scheduler.lookupParked(most, least).ifPresentOrElse(
                            this::deliverWake,
                            () -> deliverWake(new HeapFlowContext(
                                    most, least, "", 0, FlowState.PARKED, Long.MAX_VALUE)));
                }
                case ChoreographyDecision.Start(FlowExecutionPlan plan, long most, long least) -> {
                    long timeoutNanos = System.nanoTime() + plan.timeoutDurationNanos();
                    scheduler.schedule(
                            plan,
                            new HeapFlowContext(
                                    most,
                                    least,
                                    plan.definitionName(),
                                    0,
                                    FlowState.CREATED,
                                    timeoutNanos));
                }
                case ChoreographyDecision.Ignore() -> { /* intentional no-op */ }
            }
        }
    }

    /**
     * Hands one wake to the engine, absorbing only the refusal that means the instance is already
     * running. Every other {@code EX-FLOW-7002} still propagates.
     */
    private void deliverWake(FlowContext context) {
        try {
            scheduler.wake(context);
        } catch (FlowEngineException ex) {
            if (!FlowEngineException.isNotParked(ex)) {
                throw ex;
            }
        }
    }
}
