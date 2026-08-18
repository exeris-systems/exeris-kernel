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
import eu.exeris.kernel.spi.events.EventPayload;
import eu.exeris.kernel.spi.exceptions.flow.FlowEngineException;
import eu.exeris.kernel.spi.flow.ChoreographyDecision;
import eu.exeris.kernel.spi.flow.FlowChoreographyMapper;
import eu.exeris.kernel.spi.flow.FlowScheduler;
import eu.exeris.kernel.spi.flow.model.FlowContext;
import eu.exeris.kernel.spi.flow.model.FlowExecutionPlan;
import eu.exeris.kernel.spi.flow.model.FlowState;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The choreographed wake is check-then-act, and this pins what happens when it loses.
 *
 * <p>{@code lookupParked(...).ifPresent(wake)} cannot be made atomic from outside the engine, so an
 * instance present at lookup can be woken by another thread before the wake lands. The bridge's own
 * comment already called the absent case an idempotent no-op; it did not cover this one, and the
 * refusal propagated out of an event handler. Surfaced by
 * {@code CoreFlowEngineTest.immediateScheduleParkWakeOnSameContextIsRaceSafe} failing intermittently
 * on exactly that refusal.
 */
@DisplayName("FlowChoreographyBridge: the wake race")
class FlowChoreographyBridgeWakeRaceTest {

    private static final EventDescriptor WAKE_EVENT =
            new EventDescriptor(1L, 2L, 3L, 4L, 0, 0, 0L);
    private static final long MOST = 42L;
    private static final long LEAST = 7L;

    private static FlowContext parkedContext() {
        return new FlowContext() {
            @Override
            public long instanceIdMost() {
                return MOST;
            }

            @Override
            public long instanceIdLeast() {
                return LEAST;
            }

            @Override
            public String definitionName() {
                return "choreographed";
            }

            @Override
            public int currentStep() {
                return 0;
            }

            @Override
            public FlowState state() {
                return FlowState.PARKED;
            }

            @Override
            public long timeoutNanos() {
                return System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
            }
        };
    }

    /** A scheduler that always finds the instance parked and fails the wake with {@code failure}. */
    private static FlowScheduler schedulerFailingWake(RuntimeException failure, AtomicInteger wakes) {
        return new FlowScheduler() {
            @Override
            public void schedule(FlowExecutionPlan plan, FlowContext context) {
                throw new UnsupportedOperationException("not used");
            }

            @Override
            public void park(FlowContext context) {
                throw new UnsupportedOperationException("not used");
            }

            @Override
            public void wake(FlowContext context) {
                wakes.incrementAndGet();
                throw failure;
            }

            @Override
            public Optional<FlowContext> lookupParked(long instanceIdMost, long instanceIdLeast) {
                return Optional.of(parkedContext());
            }
        };
    }

    private static FlowChoreographyBridge bridgeWith(FlowScheduler scheduler) {
        FlowChoreographyMapper mapper = descriptor -> new ChoreographyDecision.Wake(MOST, LEAST);
        return new FlowChoreographyBridge(mapper, scheduler);
    }

    @Test
    @DisplayName("losing the race to another waker is not a fault — the instance is running either way")
    void notParkedRefusalIsAbsorbed() {
        AtomicInteger wakes = new AtomicInteger();
        FlowChoreographyBridge bridge = bridgeWith(schedulerFailingWake(
                FlowEngineException.notParked("test-engine", MOST, LEAST), wakes));

        assertThatCode(() -> bridge.handle(WAKE_EVENT, EventPayload.empty()))
                .as("the event asked for the instance to be running, and it is")
                .doesNotThrowAnyException();
        assertThat(wakes).hasValue(1);
    }

    @Test
    @DisplayName("every other engine refusal still propagates out of the handler")
    void otherRefusalsStillPropagate() {
        // The half that makes the absorption safe rather than a blanket catch: a queue-full or
        // schema-mismatch refusal is a real fault and must not be swallowed by the same clause.
        AtomicInteger wakes = new AtomicInteger();
        FlowEngineException other = FlowEngineException.schedulerFull("test-engine", 128);
        FlowChoreographyBridge bridge = bridgeWith(schedulerFailingWake(other, wakes));

        assertThatThrownBy(() -> bridge.handle(WAKE_EVENT, EventPayload.empty()))
                .isSameAs(other);
    }

    private static org.assertj.core.api.AtomicIntegerAssert assertThat(AtomicInteger actual) {
        return org.assertj.core.api.Assertions.assertThat(actual);
    }
}
