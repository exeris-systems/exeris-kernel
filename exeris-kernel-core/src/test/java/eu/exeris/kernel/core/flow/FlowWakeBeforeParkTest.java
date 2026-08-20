/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.core.flow;

import eu.exeris.kernel.spi.flow.FlowEngineCapabilities;
import eu.exeris.kernel.spi.flow.FlowEngineConfig;
import eu.exeris.kernel.spi.flow.model.FlowContext;
import eu.exeris.kernel.spi.flow.model.FlowDefinition;
import eu.exeris.kernel.spi.flow.model.FlowExecutionPlan;
import eu.exeris.kernel.spi.flow.model.FlowOutcome;
import eu.exeris.kernel.spi.flow.model.FlowState;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.nio.charset.StandardCharsets;
import eu.exeris.kernel.spi.events.EventDescriptor;
import eu.exeris.kernel.spi.events.EventPayload;
import eu.exeris.kernel.spi.flow.ChoreographyDecision;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The choreographed wake that arrives before the saga parks.
 *
 * <p>{@code FlowChoreographyBridge} reads an absent {@code lookupParked} as "stale or duplicate
 * wake event, idempotent no-op". There is a third case it does not name: the instance is live and
 * still running, on its way to the park this wake answers. {@code lookupParked} filters on
 * {@code state() == PARKED}, so it reports that instance absent, the bridge's {@code ifPresent}
 * skips, and {@code wake()} is never called - which means {@code wakePending}, the mechanism built
 * to hold exactly this wake, is never armed.
 *
 * <p>The engine is otherwise careful about this: {@code beginScheduleAfterWake} defers a wake that
 * lands on a running instance, and {@code abandonScheduleAfterWake} puts one back when a launch
 * fails, both on the stated grounds that "a choreography wake is one event per business trigger,
 * not a poll". A wake dropped at lookup is the same loss, one step earlier.
 *
 * <p>Shape: whoever wins the race settles fast; whoever loses it never settles at all.
 */
@DisplayName("Flow: a choreographed wake arriving before the park must not be lost")
class FlowWakeBeforeParkTest {

    @Test
    @Timeout(value = 15, unit = TimeUnit.SECONDS)
    @DisplayName("wake delivered while the step is still running still resumes the saga")
    void wakeArrivingBeforeParkStillResumesTheSaga() throws InterruptedException {
        CountDownLatch inStep = new CountDownLatch(1);
        CountDownLatch releaseStep = new CountDownLatch(1);
        CountDownLatch resumed = new CountDownLatch(1);

        try (CoreFlowEngine engine = startedEngine()) {
            FlowDefinition definition = engine.plans().newDefinition("wake-before-park")
                    .step("call-gateway", _ -> {
                        inStep.countDown();
                        awaitQuietly(releaseStep);
                        return FlowOutcome.PARK;
                    }, null)
                    .step("settle", _ -> {
                        resumed.countDown();
                        return FlowOutcome.COMPLETE;
                    }, null)
                    .build();

            FlowExecutionPlan plan = engine.plans().compile(definition);
            FlowContext context = context("wake-before-park-instance", definition.name());
            engine.scheduler().schedule(plan, context);

            assertThat(inStep.await(5, TimeUnit.SECONDS))
                    .as("the step must be running before the wake is delivered")
                    .isTrue();

            // The gateway callback lands while the saga has issued its call and has not parked
            // yet. Driven through FlowChoreographyBridge, because that is where the decision to
            // deliver or drop this wake is made.
            FlowChoreographyBridge bridge = new FlowChoreographyBridge(
                    _ -> new ChoreographyDecision.Wake(
                            context.instanceIdMost(), context.instanceIdLeast()),
                    engine.scheduler());
            bridge.handle(new EventDescriptor(1L, 2L, 3L, 4L, 0, 0, 0L), EventPayload.empty());

            releaseStep.countDown();

            assertThat(resumed.await(5, TimeUnit.SECONDS))
                    .as("the saga must resume: the wake was delivered once, and a choreography "
                        + "wake is one event per business trigger, so nothing will send it again")
                    .isTrue();
        }
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }

    private static CoreFlowEngine startedEngine() {
        FlowEngineConfig defaults = FlowEngineConfig.defaults("FlowWakeBeforeParkTest");
        CoreFlowEngine engine = new CoreFlowEngine(
                defaults, FlowEngineCapabilities.COMMUNITY.withProvider("core-flow-test"));
        engine.start();
        return engine;
    }

    private static FlowContext context(String instanceId, String definitionName) {
        UUID uuid = UUID.nameUUIDFromBytes(instanceId.getBytes(StandardCharsets.UTF_8));
        return new FlowContext() {
            @Override
            public long instanceIdMost() {
                return uuid.getMostSignificantBits();
            }

            @Override
            public long instanceIdLeast() {
                return uuid.getLeastSignificantBits();
            }

            @Override
            public String definitionName() {
                return definitionName;
            }

            @Override
            public int currentStep() {
                return 0;
            }

            @Override
            public FlowState state() {
                return FlowState.CREATED;
            }

            @Override
            public long timeoutNanos() {
                return Long.MAX_VALUE;
            }
        };
    }
}
