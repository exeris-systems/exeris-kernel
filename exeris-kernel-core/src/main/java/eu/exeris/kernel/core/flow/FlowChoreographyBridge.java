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
                    // One call into the engine, which resolves by key under its own lock. The
                    // two-call form this replaces - lookupParked(...).ifPresent(wake) - was
                    // check-then-act by its own admission, and its two failure modes were not
                    // symmetric: an instance still inside the step about to PARK reported absent
                    // and the wake was dropped for good, while a genuinely unknown key paid a
                    // second durable-store probe on the way to the same refusal.
                    //
                    // NOT_PARKED is still absorbed: it now means only what it always should have,
                    // that the instance is already running. Every other EX-FLOW-7002 propagates.
                    try {
                        scheduler.wake(most, least);
                    } catch (FlowEngineException ex) {
                        if (!FlowEngineException.isNotParked(ex)) {
                            throw ex;
                        }
                    }
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

}
