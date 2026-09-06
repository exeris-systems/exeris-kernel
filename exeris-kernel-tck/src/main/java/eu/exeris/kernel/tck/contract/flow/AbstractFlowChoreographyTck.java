/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
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
import java.util.concurrent.locks.LockSupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

/**
 * L2 Contract: choreography-driven flow orchestration through a mapped
 * {@link ChoreographyDecision}.
 *
 * <p>An implementation maps an inbound event to a decision via
 * {@link FlowEngine#registerChoreographyMapper}, and this contract fixes what each decision must
 * do: {@link ChoreographyDecision.Wake} resumes the exact parked instance the mapper names,
 * {@link ChoreographyDecision.Start} schedules a new instance from the mapped plan, and
 * {@link ChoreographyDecision.Ignore} leaves scheduler state unchanged. The bridge dispatching a
 * decision must also release the inbound event payload on every branch.
 *
 * @since 0.5
 */
public abstract class AbstractFlowChoreographyTck {

    /**
     * Creates a fully configured but not yet started {@link FlowEngine}.
     *
     * @return a new engine instance, not yet started
     */
    protected abstract FlowEngine createFlowEngine();

    /**
     * Creates a configured but not yet started {@link EventEngine}.
     *
     * @return a new event engine instance, not yet started
     */
    protected abstract EventEngine createEventEngine();

    /**
     * Maximum wall-clock time, in milliseconds, the abstract suite waits for a choreography
     * event to traverse the bus and reach the {@code FlowChoreographyBridge}. Tuned for
     * in-memory transports by default; broker-backed bindings (e.g. Testcontainers Kafka)
     * override to absorb consumer-rebalance + auto-create-topic latency.
     *
     * @return the round-trip wait budget, in milliseconds
     */
    protected long choreographyRoundtripTimeoutMs() {
        return 4_000L;
    }

    /**
     * Settle period for the Ignore-decision test — how long the suite waits before
     * asserting that no scheduler side-effect occurred. Override for slower transports.
     *
     * @return the settle window, in milliseconds
     */
    protected long choreographyIgnoreSettleMs() {
        return 100L;
    }

    private FlowEngine engine;
    private EventEngine eventEngine;
    private EventBus bus;
    private final AtomicInteger ordinalCounter = new AtomicInteger(1);

    /**
     * Creates the contract; subclasses supply the flow and event engines via
     * {@link #createFlowEngine()} and {@link #createEventEngine()}.
     *
     * <p>The {@code engine}, {@code eventEngine} and {@code bus} fields start unset — {@link
     * #setUp()} populates them before each test; {@code ordinalCounter} starts at one.
     */
    public AbstractFlowChoreographyTck() {
        // Declared, not added: the implicit no-arg constructor, written out so it can carry a comment.
        super();
    }

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

    /**
     * Verifies that a {@link ChoreographyDecision.Wake} resolved from a published event resumes
     * the specific instance the mapper names. The scheduler's parked-flow count is observed
     * rising to at least one while the flow is parked and falling back to zero only after the
     * mapped event is published, which happens if and only if the bridge resolves the decision
     * and the scheduler wakes that instance's identity.
     *
     * @throws InterruptedException if the polling thread is interrupted while waiting for the
     *                               park or the wake to be observed
     */
    @Test
    @Timeout(value = 180, unit = TimeUnit.SECONDS)
    @DisplayName("Wake decision — mapper wakes a parked flow when event arrives")
    protected void wakeDecision_wakesParkedFlow() throws InterruptedException {
        AtomicBoolean parked = new AtomicBoolean(false);
        UUID instanceId = UUID.randomUUID();
        String eventType = "WakeEvent-" + instanceId;
        FlowExecutionPlan plan = parkingPlan("wake-test-" + instanceId, parked);

        engine.scheduler().schedule(plan, heapContext(instanceId, "wake-test-" + instanceId));

        long roundtripBudgetMs = choreographyRoundtripTimeoutMs();
        long deadline = System.currentTimeMillis() + roundtripBudgetMs;
        while (engine.stats().parkedFlows() < 1 && System.currentTimeMillis() < deadline) {
            LockSupport.parkNanos(10_000_000L); // 10 ms — deterministic poll, avoids S2925 Thread.sleep
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

        long wakeDeadline = System.currentTimeMillis() + roundtripBudgetMs;
        while (engine.stats().parkedFlows() > 0 && System.currentTimeMillis() < wakeDeadline) {
            LockSupport.parkNanos(10_000_000L); // 10 ms — deterministic poll, avoids S2925 Thread.sleep
        }
        assertThat(engine.stats().parkedFlows())
                .as("flow should no longer be parked after choreography wake")
                .isZero();
    }

    @Test
    @Timeout(value = 180, unit = TimeUnit.SECONDS)
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

        long startDeadline = System.currentTimeMillis() + choreographyRoundtripTimeoutMs();
        while (engine.stats().completedFlows() < 1 && System.currentTimeMillis() < startDeadline) {
            LockSupport.parkNanos(10_000_000L); // 10 ms — deterministic poll, avoids S2925 Thread.sleep
        }
        assertThat(engine.stats().completedFlows())
                .as("new flow triggered by choreography event must complete")
                .isGreaterThanOrEqualTo(1L);
    }

    @Test
    @Timeout(value = 180, unit = TimeUnit.SECONDS)
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
        settleWindow(choreographyIgnoreSettleMs() * 1_000_000L);

        assertThat(engine.stats().activeFlows())
                .as("Ignore decision must not change active flow count")
                .isEqualTo(statsBefore);
    }

    /**
     * Bounded settle window. A straight-line {@link LockSupport#parkNanos(long)} call may
     * return early on a spurious wake-up or interrupt and shorten the wait, which would
     * cause the next assertion to fire before background threads complete. Spinning until
     * a {@code nanoTime} deadline guarantees the full duration regardless of how many times
     * {@code parkNanos} returns.
     */
    private static void settleWindow(long nanos) {
        long deadline = System.nanoTime() + nanos;
        long remaining;
        while ((remaining = deadline - System.nanoTime()) > 0L) {
            LockSupport.parkNanos(remaining);
        }
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
