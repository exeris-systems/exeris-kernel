/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
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
 * <h2>Repeated Start</h2>
 * <p>A {@code Start} for an instance the engine already holds is absorbed, not refused: delivery
 * is at least once, so a redelivered event is expected. The bridge reports the absorbed case through
 * {@link ChoreographyStartIgnoredEvent} and does not throw, because a throwing handler would make a
 * broker-backed binding redeliver the same event.
 *
 * @since 0.5
 */
final class FlowChoreographyBridge implements EventHandler {

    private final FlowChoreographyMapper mapper;
    private final FlowScheduler scheduler;
    private final CoreFlowRuntime runtime;

    /**
     * Wires this bridge to the mapper that decides what an incoming event means for a saga and to the
     * engine that carries out that decision.
     *
     * @param mapper    translates an event descriptor into a {@link ChoreographyDecision}; must not
     *                  be {@code null}
     * @param scheduler receives the resulting {@code wake} call; must not be {@code null}. In the
     *                  engine this is {@code runtime.scheduler()}
     * @param runtime   receives the resulting {@code Start}, and reports what it did with it; must
     *                  not be {@code null}
     * @throws NullPointerException if any argument is {@code null}
     */
    /* default */ FlowChoreographyBridge(
            FlowChoreographyMapper mapper, FlowScheduler scheduler, CoreFlowRuntime runtime) {
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.runtime = Objects.requireNonNull(runtime, "runtime");
    }

    /**
     * {@inheritDoc}
     *
     * @implNote Holds no mutable state of its own, so the interface's requirement that concurrent
     *           invocations be safe is satisfied without extra synchronization here — {@code mapper}
     *           and {@code scheduler} carry whatever thread-safety their own contracts already
     *           require.
     */
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
                    // NOT migrated (ADR-082). This runs on whichever thread drives the
                    // choreography bridge, and that thread's binding state is not established —
                    // a slot read here would silently return the system clock, which is the
                    // "looks migrated, is not drivable" failure the ADR names. Threading a
                    // captured source in belongs with the rest of the choreography path.
                    long timeoutNanos = System.nanoTime() + plan.timeoutDurationNanos();
                    CoreFlowRuntime.ScheduleOutcome outcome = runtime.submitStart(
                            plan,
                            new HeapFlowContext(
                                    most,
                                    least,
                                    plan.definitionName(),
                                    0,
                                    FlowState.CREATED,
                                    timeoutNanos));
                    // A Start for an instance the engine already holds is the case an operator needs
                    // to see; a run that never launched held no instance and is not reported here.
                    if (outcome.absorbed()) {
                        ChoreographyStartIgnoredEvent.emit(
                                runtime.engineName(), most, least,
                                runtime.existingState(new FlowKey(most, least), outcome));
                    }
                }
                case ChoreographyDecision.Ignore() -> { /* intentional no-op */ }
            }
        }
    }

}
