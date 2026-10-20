/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.core.flow;

import eu.exeris.kernel.spi.events.EventDescriptor;
import eu.exeris.kernel.spi.events.EventPayload;
import eu.exeris.kernel.spi.flow.ChoreographyDecision;
import eu.exeris.kernel.spi.flow.FlowChoreographyMapper;
import eu.exeris.kernel.spi.flow.FlowEngineCapabilities;
import eu.exeris.kernel.spi.flow.FlowEngineConfig;
import eu.exeris.kernel.spi.flow.model.FlowDefinition;
import eu.exeris.kernel.spi.flow.model.FlowExecutionPlan;
import eu.exeris.kernel.spi.flow.model.FlowOutcome;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordingStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * A choreography {@code Start} for an instance the engine already holds is absorbed.
 *
 * <p>Drives {@link FlowChoreographyBridge#handle} directly, so each case observes the bridge's own
 * contract rather than a binding's transport: the second Start must not throw, must not run the flow
 * again, and must leave one {@code ChoreographyStartIgnored} event naming the state it found. The
 * async bindings in the TCK cannot see a refusal, which is why the refusal direction is pinned here.
 */
@DisplayName("FlowChoreographyBridge: a repeated Start is absorbed and reported")
class FlowChoreographyBridgeDuplicateStartTest {

    private static final String ENGINE_NAME = "duplicate-start-test";
    private static final String IGNORED_EVENT = "eu.exeris.kernel.flow.ChoreographyStartIgnored";
    private static final long MOST = 0x5EED_0001L;
    private static final long LEAST = 0x5EED_0002L;
    private static final EventDescriptor DESCRIPTOR = new EventDescriptor(1L, 2L, 3L, 4L, 0, 0, 0L);
    private static final long SETTLE_NANOS = TimeUnit.SECONDS.toNanos(5);

    @Test
    @Timeout(value = 20, unit = TimeUnit.SECONDS)
    @DisplayName("a Start for a running instance is absorbed, runs no second step, and says RUNNING")
    void runningInstanceIsAbsorbed() throws Exception {
        AtomicInteger runs = new AtomicInteger();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Queue<RecordedEvent> events = new ConcurrentLinkedQueue<>();

        try (CoreFlowEngine engine = startedEngine();
             RecordingStream rs = recordIgnoredStarts(events)) {
            FlowDefinition definition = engine.plans().newDefinition("duplicate-start-running")
                    .step("hold", _ -> {
                        runs.incrementAndGet();
                        entered.countDown();
                        awaitQuietly(release);
                        return FlowOutcome.COMPLETE;
                    }, null)
                    .build();
            FlowChoreographyBridge bridge = bridgeFor(engine, definition);

            try {
                bridge.handle(DESCRIPTOR, EventPayload.empty());
                assertThat(entered.await(5, TimeUnit.SECONDS))
                        .as("the first Start must start the flow").isTrue();

                assertThatCode(() -> bridge.handle(DESCRIPTOR, EventPayload.empty()))
                        .as("a repeated Start is absorbed, not refused")
                        .doesNotThrowAnyException();
                RecordedEvent event = awaitSingleEvent(events);
                assertThat(event.getString("existingState")).isEqualTo("RUNNING");
                assertThat(engine.stats().completedFlows())
                        .as("a repeated Start must not launch a second instance that completes while the first is held")
                        .isZero();
            } finally {
                // Released on every path, so a failed assertion above never leaves the held step running.
                release.countDown();
            }
            awaitUntil(() -> engine.stats().activeFlows() == 0);
            assertThat(engine.stats().completedFlows()).as("one completion for two Starts").isEqualTo(1L);
            assertThat(runs).as("the flow must run exactly once").hasValue(1);
        }
    }

    @Test
    @Timeout(value = 20, unit = TimeUnit.SECONDS)
    @DisplayName("a Start for a parked instance is absorbed, stays parked, and says PARKED")
    void parkedInstanceIsAbsorbed() throws Exception {
        AtomicInteger runs = new AtomicInteger();
        Queue<RecordedEvent> events = new ConcurrentLinkedQueue<>();

        try (CoreFlowEngine engine = startedEngine();
             RecordingStream rs = recordIgnoredStarts(events)) {
            FlowDefinition definition = engine.plans().newDefinition("duplicate-start-parked")
                    .step("wait", _ -> {
                        runs.incrementAndGet();
                        return FlowOutcome.PARK;
                    }, null)
                    .step("done", _ -> FlowOutcome.COMPLETE, null)
                    .transition(0, 1)
                    .build();
            FlowChoreographyBridge bridge = bridgeFor(engine, definition);

            bridge.handle(DESCRIPTOR, EventPayload.empty());
            // activeFlows drops only after the run thread has cleared its schedule claim, so the
            // second Start sees the parked instance and not a run that is still finishing.
            awaitUntil(() -> engine.stats().parkedFlows() == 1L && engine.stats().activeFlows() == 0);

            assertThatCode(() -> bridge.handle(DESCRIPTOR, EventPayload.empty()))
                    .as("a repeated Start is absorbed, not refused")
                    .doesNotThrowAnyException();
            RecordedEvent event = awaitSingleEvent(events);
            assertThat(event.getString("existingState")).isEqualTo("PARKED");

            assertThat(engine.stats().parkedFlows())
                    .as("the parked instance must not be registered a second time").isEqualTo(1L);
            assertThat(runs).as("the wait step must run exactly once").hasValue(1);
        }
    }

    @Test
    @Timeout(value = 20, unit = TimeUnit.SECONDS)
    @DisplayName("a Start for a completed instance is absorbed, runs nothing, and says COMPLETED")
    void completedInstanceIsAbsorbed() throws Exception {
        AtomicInteger runs = new AtomicInteger();
        Queue<RecordedEvent> events = new ConcurrentLinkedQueue<>();

        try (CoreFlowEngine engine = startedEngine();
             RecordingStream rs = recordIgnoredStarts(events)) {
            FlowDefinition definition = engine.plans().newDefinition("duplicate-start-completed")
                    .step("only", _ -> {
                        runs.incrementAndGet();
                        return FlowOutcome.COMPLETE;
                    }, null)
                    .build();
            FlowChoreographyBridge bridge = bridgeFor(engine, definition);

            bridge.handle(DESCRIPTOR, EventPayload.empty());
            awaitUntil(() -> engine.stats().completedFlows() == 1L);

            assertThatCode(() -> bridge.handle(DESCRIPTOR, EventPayload.empty()))
                    .as("a repeated Start is absorbed, not refused")
                    .doesNotThrowAnyException();
            RecordedEvent event = awaitSingleEvent(events);
            assertThat(event.getString("existingState")).isEqualTo("COMPLETED");

            assertThat(runs).as("a completed flow must not run again").hasValue(1);
            assertThat(engine.stats().completedFlows()).isEqualTo(1L);
        }
    }

    private static CoreFlowEngine startedEngine() {
        CoreFlowEngine engine = new CoreFlowEngine(
                FlowEngineConfig.defaults(ENGINE_NAME),
                FlowEngineCapabilities.COMMUNITY.withProvider("test"));
        engine.start();
        return engine;
    }

    /** A bridge whose every event asks for the same instance to be started from the definition. */
    private static FlowChoreographyBridge bridgeFor(CoreFlowEngine engine, FlowDefinition definition) {
        FlowExecutionPlan plan = engine.plans().compile(definition);
        FlowChoreographyMapper mapper = _ -> new ChoreographyDecision.Start(plan, MOST, LEAST);
        return new FlowChoreographyBridge(mapper, engine.scheduler(), engine.runtime());
    }

    /**
     * Opens a recording of the absorbed-Start event and waits until the stream is live, so an event
     * emitted straight after this returns is not lost before the stream starts reading.
     */
    private static RecordingStream recordIgnoredStarts(Queue<RecordedEvent> events) throws InterruptedException {
        RecordingStream rs = new RecordingStream();
        rs.enable(IGNORED_EVENT);
        rs.onEvent(IGNORED_EVENT, events::add);
        CountDownLatch live = new CountDownLatch(1);
        rs.onFlush(live::countDown);
        rs.startAsync();
        assertThat(live.await(10, TimeUnit.SECONDS))
                .as("the recording stream must be live before any event is emitted")
                .isTrue();
        return rs;
    }

    /** Waits for the one event a single absorbed Start produces, and checks there is no second. */
    private static RecordedEvent awaitSingleEvent(Queue<RecordedEvent> events) {
        awaitUntil(() -> !events.isEmpty());
        LockSupport.parkNanos(200_000_000L); // a late duplicate emit must be seen by the size check
        assertThat(events).as("one absorbed Start, one ChoreographyStartIgnored event").hasSize(1);
        RecordedEvent event = events.peek();
        assertThat(event.getString("engineName")).isEqualTo(ENGINE_NAME);
        assertThat(event.getLong("instanceIdMost")).isEqualTo(MOST);
        assertThat(event.getLong("instanceIdLeast")).isEqualTo(LEAST);
        return event;
    }

    private static void awaitUntil(BooleanSupplier condition) {
        long deadline = System.nanoTime() + SETTLE_NANOS;
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
            LockSupport.parkNanos(10_000_000L); // 10 ms poll
        }
        assertThat(condition.getAsBoolean()).as("condition not reached within the wait budget").isTrue();
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException _) {
            Thread.currentThread().interrupt();
        }
    }
}
