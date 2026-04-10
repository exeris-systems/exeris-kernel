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
import eu.exeris.kernel.spi.flow.model.FlowState;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordingStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("CoreFlowRuntime - JFR telemetry")
class CoreFlowRuntimeTest {

    private static final String STEP_FAILED_EVENT = "eu.exeris.kernel.flow.StepFailed";

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    @DisplayName("emits FlowStepFailedEvent when a step throws")
    void emitsFlowStepFailedEventWhenStepThrows() throws Exception {
        CountDownLatch eventReceived = new CountDownLatch(1);
        AtomicReference<RecordedEvent> captured = new AtomicReference<>();

        try (CoreFlowEngine engine = startedEngine();
             RecordingStream rs = new RecordingStream()) {

            rs.enable(STEP_FAILED_EVENT);
            rs.onEvent(STEP_FAILED_EVENT, event -> {
                captured.compareAndSet(null, event);
                eventReceived.countDown();
            });
            rs.startAsync();

            FlowDefinition definition = engine.plans().newDefinition("jfr-step-fail")
                    .step("exploding-step", _ -> {
                        throw new RuntimeException("step-exploded");
                    }, null)
                    .build();

            FlowExecutionPlan plan = engine.plans().compile(definition);
            FlowContext context = context("jfr-fail-instance", definition.name());

            engine.scheduler().schedule(plan, context);

            assertThat(eventReceived.await(3, TimeUnit.SECONDS))
                    .as("eu.exeris.kernel.flow.StepFailed must be emitted within 3 s")
                    .isTrue();

            RecordedEvent event = captured.get();
            assertThat(event.getInt("stepIndex")).isEqualTo(0);
            assertThat(event.getString("failureReason")).contains("step-exploded");
            assertThat(event.getLong("instanceIdMost") | event.getLong("instanceIdLeast"))
                    .isNotZero();
        }
    }

    private static CoreFlowEngine startedEngine() {
        FlowEngineConfig defaults = FlowEngineConfig.defaults("CoreFlowRuntimeTest");
        CoreFlowEngine engine = new CoreFlowEngine(
                new FlowEngineConfig(
                        defaults.engineName(),
                        defaults.maxConcurrentFlows(),
                        defaults.timeoutDurationNanos(),
                        defaults.maxSteps(),
                        defaults.maxTransitions(),
                        defaults.maxExecutionPlans(),
                        defaults.schedulerQueueCapacity(),
                        defaults.partitionName(),
                        defaults.partitionBytes(),
                        false,
                        defaults.compensationEnabled()
                ),
                FlowEngineCapabilities.COMMUNITY.withProvider("core-flow-runtime-test")
        );
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
                return FlowState.RUNNING;
            }

            @Override
            public long timeoutNanos() {
                return System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
            }
        };
    }
}
