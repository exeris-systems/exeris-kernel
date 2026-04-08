/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.tck.contract.flow;

import eu.exeris.kernel.spi.events.EventBus;
import eu.exeris.kernel.spi.events.EventDescriptor;
import eu.exeris.kernel.spi.events.EventEngine;
import eu.exeris.kernel.spi.events.EventPayload;
import eu.exeris.kernel.spi.events.EventTypeSpec;
import eu.exeris.kernel.spi.flow.ChoreographyDecision;
import eu.exeris.kernel.spi.flow.FlowEngine;
import eu.exeris.kernel.spi.flow.model.FlowContext;
import eu.exeris.kernel.spi.flow.model.FlowExecutionPlan;
import eu.exeris.kernel.spi.flow.model.FlowOutcome;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

/**
 * TCK: Abstract base for choreography-driven flow orchestration contract verification.
 *
 * @since 0.6.0
 */
public abstract class AbstractFlowChoreographyTck {

    /** Creates a fully configured but not yet started {@link FlowEngine}. */
    protected abstract FlowEngine createFlowEngine();

    /** Creates a configured but not yet started {@link EventEngine}. */
    protected abstract EventEngine createEventEngine();

    private FlowEngine engine;
    private EventEngine eventEngine;
    private EventBus bus;
    private final AtomicInteger ordinalCounter = new AtomicInteger(1);

    @BeforeEach
    final void setUp() {
        engine = createFlowEngine();
        engine.start();
        eventEngine = createEventEngine();
        eventEngine.start();
        bus = eventEngine.bus();
    }

    @AfterEach
    final void tearDown() {
        engine.close();
        eventEngine.close();
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /**
     * Registers an event type in the engine's registry and returns the assigned ordinal.
     * The returned ordinal must be used when constructing the published {@link EventDescriptor}
     * so routing reaches the subscribed handler.
     */
    private int registerType(String name) {
        int ordinal = ordinalCounter.getAndIncrement();
        eventEngine.registry().register(EventTypeSpec.of(name, ordinal));
        return ordinal;
    }

    private FlowExecutionPlan singleStepPlan(String defName) {
        return engine.plans().compile(
                engine.plans().newDefinition(defName)
                      .step("only-step", ctx -> FlowOutcome.COMPLETE, null)
                      .build());
    }

    private FlowExecutionPlan parkingPlan(String defName, AtomicBoolean parkedFlag) {
        return engine.plans().compile(
                engine.plans().newDefinition(defName)
                      .step("wait-step", ctx -> {
                          parkedFlag.set(true);
                          return FlowOutcome.PARK;
                      }, null)
                      .step("done-step", ctx -> FlowOutcome.COMPLETE, null)
                      .transition(0, 1)
                      .build());
    }

    private static EventDescriptor descriptorForOrdinal(int ordinal) {
        return EventDescriptor.of(0L, 0L, 0L, 0L, ordinal, 0, System.currentTimeMillis());
    }

    private static FlowContext heapContext(UUID id, String defName) {
        return new SimpleFlowContext(
                id.getMostSignificantBits(),
                id.getLeastSignificantBits(),
                defName);
    }

    // -------------------------------------------------------------------------
    // Tests
    // -------------------------------------------------------------------------

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    @DisplayName("Wake decision — mapper wakes a parked flow when event arrives")
    void wakeDecision_wakesParkedFlow() throws InterruptedException {
        AtomicBoolean parked = new AtomicBoolean(false);
        UUID instanceId = UUID.randomUUID();
        String eventType = "WakeEvent-" + instanceId;
        FlowExecutionPlan plan = parkingPlan("wake-test-" + instanceId, parked);

        engine.scheduler().schedule(plan, heapContext(instanceId, "wake-test-" + instanceId));

        long deadline = System.currentTimeMillis() + 4_000;
        while (engine.stats().parkedFlows() < 1 && System.currentTimeMillis() < deadline) {
            Thread.sleep(10);
        }
        assertThat(engine.stats().parkedFlows())
                .as("flow must be in PARKED state before wake")
                .isGreaterThanOrEqualTo(1L);

        long most  = instanceId.getMostSignificantBits();
        long least = instanceId.getLeastSignificantBits();

        int ordinal = registerType(eventType);
        engine.registerChoreographyMapper(
                descriptor -> new ChoreographyDecision.Wake(most, least),
                List.of(eventType),
                bus);

        bus.publish(descriptorForOrdinal(ordinal), EventPayload.empty());

        Thread.sleep(500);
        assertThat(engine.stats().parkedFlows())
                .as("flow should no longer be parked after choreography wake")
                .isZero();
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    @DisplayName("Start decision — mapper schedules a new flow instance from event")
    void startDecision_schedulesNewFlow() throws InterruptedException {
        UUID instanceId = UUID.randomUUID();
        String eventType = "StartEvent-" + instanceId;
        FlowExecutionPlan plan = singleStepPlan("start-test-" + instanceId);

        int ordinal = registerType(eventType);
        engine.registerChoreographyMapper(
                descriptor -> new ChoreographyDecision.Start(
                        plan,
                        instanceId.getMostSignificantBits(),
                        instanceId.getLeastSignificantBits()),
                List.of(eventType),
                bus);

        bus.publish(descriptorForOrdinal(ordinal), EventPayload.empty());

        Thread.sleep(500);
        assertThat(engine.stats().completedFlows())
                .as("new flow triggered by choreography event must complete")
                .isGreaterThanOrEqualTo(1L);
    }

    @Test
    @Timeout(value = 3, unit = TimeUnit.SECONDS)
    @DisplayName("Ignore decision — has no side-effects on scheduler state")
    void ignoreDecision_hasNoSideEffects() throws InterruptedException {
        long statsBefore = engine.stats().activeFlows();
        String eventType = "IgnoreEvent-" + UUID.randomUUID();

        int ordinal = registerType(eventType);
        engine.registerChoreographyMapper(
                descriptor -> new ChoreographyDecision.Ignore(),
                List.of(eventType),
                bus);

        bus.publish(descriptorForOrdinal(ordinal), EventPayload.empty());
        Thread.sleep(100);

        assertThat(engine.stats().activeFlows())
                .as("Ignore decision must not change active flow count")
                .isEqualTo(statsBefore);
    }

    @Test
    @DisplayName("Bridge — payload is closed on all decision branches (RAII)")
    void bridge_closesPayloadOnAllPaths() {
        FlowExecutionPlan plan = singleStepPlan("raii-test-" + UUID.randomUUID());
        UUID id = UUID.randomUUID();

        String wakeType   = "PayloadWake-" + id;
        String startType  = "PayloadStart-" + id;
        String ignoreType = "PayloadIgnore-" + id;

        int o1 = registerType(wakeType);
        int o2 = registerType(startType);
        int o3 = registerType(ignoreType);

        engine.registerChoreographyMapper(
                d -> new ChoreographyDecision.Wake(
                        id.getMostSignificantBits(), id.getLeastSignificantBits()),
                List.of(wakeType), bus);

        engine.registerChoreographyMapper(
                d -> new ChoreographyDecision.Start(
                        plan,
                        id.getMostSignificantBits(), id.getLeastSignificantBits()),
                List.of(startType), bus);

        engine.registerChoreographyMapper(
                d -> new ChoreographyDecision.Ignore(),
                List.of(ignoreType), bus);

        assertDoesNotThrow(() -> {
            bus.publish(descriptorForOrdinal(o1), EventPayload.empty());
            bus.publish(descriptorForOrdinal(o2), EventPayload.empty());
            bus.publish(descriptorForOrdinal(o3), EventPayload.empty());
        });
    }
}
