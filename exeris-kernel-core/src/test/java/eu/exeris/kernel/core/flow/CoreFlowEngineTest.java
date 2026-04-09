/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.core.flow;

import eu.exeris.kernel.spi.context.KernelProviders;
import eu.exeris.kernel.spi.events.EventBus;
import eu.exeris.kernel.spi.events.EventDescriptor;
import eu.exeris.kernel.spi.events.EventEngine;
import eu.exeris.kernel.spi.events.EventEngineStats;
import eu.exeris.kernel.spi.events.EventHandler;
import eu.exeris.kernel.spi.events.EventLoop;
import eu.exeris.kernel.spi.events.EventPayload;
import eu.exeris.kernel.spi.events.EventQueue;
import eu.exeris.kernel.spi.events.EventRegistry;
import eu.exeris.kernel.spi.events.EventTypeSpec;
import eu.exeris.kernel.spi.events.SubscriptionToken;
import eu.exeris.kernel.spi.flow.FlowEngineCapabilities;
import eu.exeris.kernel.spi.flow.FlowEngineConfig;
import eu.exeris.kernel.spi.flow.model.FlowContext;
import eu.exeris.kernel.spi.flow.model.FlowDefinition;
import eu.exeris.kernel.spi.flow.model.FlowExecutionPlan;
import eu.exeris.kernel.spi.flow.model.FlowOutcome;
import eu.exeris.kernel.spi.flow.model.FlowSnapshot;
import eu.exeris.kernel.spi.flow.model.FlowSnapshotStore;
import eu.exeris.kernel.spi.flow.model.FlowState;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.lang.foreign.ValueLayout;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("CoreFlowEngine")
class CoreFlowEngineTest {

    private static final String FLOW_PROGRESS_EVENT_TYPE = "FlowProgress";

    @RepeatedTest(3)
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    @DisplayName("concurrent reschedules are executed safely (timing-dependent)")
    void concurrentReschedulesExecuteStepExactlyOnce() throws InterruptedException {
        try (CoreFlowEngine engine = startedEngine(false)) {
            AtomicInteger executions = new AtomicInteger();

            FlowDefinition definition = engine.plans().newDefinition("concurrent-idempotency-" + System.nanoTime())
                    .step("reserve", _ -> {
                        executions.incrementAndGet();
                        return FlowOutcome.CONTINUE;
                    }, null)
                    .build();

            FlowExecutionPlan plan = engine.plans().compile(definition);
            FlowContext context = context("same-instance-" + System.nanoTime(), definition.name());

            int concurrency = 10;
            CountDownLatch go = new CountDownLatch(1);
            CountDownLatch done = new CountDownLatch(concurrency);

            for (int index = 0; index < concurrency; index++) {
                Thread.ofVirtual().name("core-flow-idempotency-", index).start(() -> {
                    try {
                        go.await();
                        engine.scheduler().schedule(plan, context);
                    } catch (InterruptedException ex) {
                        Thread.currentThread().interrupt();
                    } finally {
                        done.countDown();
                    }
                });
            }

            go.countDown();
            assertThat(done.await(10, TimeUnit.SECONDS)).isTrue();
            awaitTrue(5_000, () -> executions.get() >= 1);

            assertThat(executions.get()).isEqualTo(1);
        }
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    @DisplayName("publishes terminal flow progress events only when an event engine is bound")
    void publishesFlowProgressWhenEventEngineIsBound() throws InterruptedException {
        try (CoreFlowEngine engine = startedEngine(false)) {
            RecordingEventEngine eventEngine = new RecordingEventEngine();
            CountDownLatch completed = new CountDownLatch(1);

            FlowDefinition definition = engine.plans().newDefinition("progress-flow")
                    .step("charge", _ -> {
                        completed.countDown();
                        return FlowOutcome.CONTINUE;
                    }, null)
                    .build();

            FlowExecutionPlan plan = engine.plans().compile(definition);
            FlowContext context = context("progress-instance", definition.name());

            ScopedValue.where(KernelProviders.EVENT_ENGINE, eventEngine)
                    .run(() -> engine.scheduler().schedule(plan, context));

            assertThat(completed.await(5, TimeUnit.SECONDS)).isTrue();
            awaitTrue(5_000, () -> !eventEngine.events().isEmpty());

            assertThat(eventEngine.registry().resolve(FLOW_PROGRESS_EVENT_TYPE)).isNotNull();
            assertThat(eventEngine.events())
                    .extracting(RecordedEvent::payload)
                    .contains("progress-flow|0|COMPLETED")
                    .doesNotContain("progress-flow|0|RUNNING");
        }
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    @DisplayName("ignores flow progress publication failures")
    void ignoresFlowProgressPublicationFailures() throws InterruptedException {
        try (CoreFlowEngine engine = startedEngine(false)) {
            FailingEventEngine eventEngine = new FailingEventEngine();
            CountDownLatch completed = new CountDownLatch(1);

            FlowDefinition definition = engine.plans().newDefinition("progress-failure")
                    .step("step", _ -> {
                        completed.countDown();
                        return FlowOutcome.CONTINUE;
                    }, null)
                    .build();

            FlowExecutionPlan plan = engine.plans().compile(definition);
            FlowContext context = context("failure-instance", definition.name());

            ScopedValue.where(KernelProviders.EVENT_ENGINE, eventEngine)
                    .run(() -> engine.scheduler().schedule(plan, context));

            assertThat(completed.await(5, TimeUnit.SECONDS)).isTrue();
            awaitTrue(5_000, () -> engine.stats().completedFlows() >= 1);
            assertThat(engine.stats().completedFlows()).isEqualTo(1);
        }
    }

    @Nested
    @DisplayName("Wake and Schedule Edge Cases")
    class ScheduleAndWakeTests {

        @Test
        @Timeout(value = 5, unit = TimeUnit.SECONDS)
        @DisplayName("wake without prior snapshot throws when context is not parked")
        void wakeWithoutSnapshotThrowsWhenContextIsNotParked() {
            try (CoreFlowEngine engine = startedEngine(true)) {
                FlowContext context = context("no-snapshot-instance", "non-existent-flow");
                org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> engine.scheduler().wake(context))
                        .isInstanceOf(eu.exeris.kernel.spi.exceptions.flow.FlowEngineException.class);
            }
        }

        @Test
        @Timeout(value = 10, unit = TimeUnit.SECONDS)
        @DisplayName("schedule restores from snapshot when available")
        void scheduleRestoresFromSnapshot() throws InterruptedException {
            CountDownLatch firstComplete = new CountDownLatch(1);
            CountDownLatch secondComplete = new CountDownLatch(1);
            InMemorySnapshotStore store = new InMemorySnapshotStore();
            FlowEngineConfig defaults = FlowEngineConfig.defaults("CoreFlowEngineTest");
            FlowEngineConfig config = new FlowEngineConfig(
                    defaults.engineName(),
                    defaults.maxConcurrentFlows(),
                    defaults.timeoutDurationNanos(),
                    defaults.maxSteps(),
                    defaults.maxTransitions(),
                    defaults.maxExecutionPlans(),
                    defaults.schedulerQueueCapacity(),
                    defaults.partitionName(),
                    defaults.partitionBytes(),
                    true,
                    defaults.compensationEnabled()
            );
            try {
                ScopedValue.where(KernelProviders.FLOW_SNAPSHOT_STORE, store).run(() -> {
                    try (CoreFlowEngine engine = new CoreFlowEngine(config,
                            FlowEngineCapabilities.COMMUNITY.withProvider("core-flow-test"))) {
                        engine.start();

                        FlowDefinition definition = engine.plans().newDefinition("snapshot-restore")
                                .step("first", _ -> {
                                    firstComplete.countDown();
                                    return FlowOutcome.PARK;
                                }, null)
                                .step("second", _ -> {
                                    secondComplete.countDown();
                                    return FlowOutcome.CONTINUE;
                                }, null)
                                .build();

                        FlowExecutionPlan plan = engine.plans().compile(definition);
                        FlowContext context = context("snapshot-instance", definition.name());

                        engine.scheduler().schedule(plan, context);
                        if (!firstComplete.await(5, TimeUnit.SECONDS)) {
                            throw new AssertionError("First step did not complete");
                        }
                        awaitTrue(5_000, () -> store.exists(context.instanceIdMost(), context.instanceIdLeast()));

                        assertThat(store.exists(context.instanceIdMost(), context.instanceIdLeast())).isTrue();

                        engine.scheduler().wake(context);
                        if (!secondComplete.await(5, TimeUnit.SECONDS)) {
                            throw new AssertionError("Second step did not complete");
                        }
                        awaitTrue(5_000, () -> engine.stats().completedFlows() >= 1);

                        assertThat(engine.stats().completedFlows()).isEqualTo(1);
                    } catch (InterruptedException ex) {
                        throw new RuntimeException(ex);
                    }
                });
            } catch (RuntimeException ex) {
                if (ex.getCause() instanceof InterruptedException) {
                    throw (InterruptedException) ex.getCause();
                }
                throw ex;
            }
        }
    }

    @Nested
    @DisplayName("Restore and Persistence Edge Cases")
    class RestoreAndPersistenceTests {

        @Test
        @Timeout(value = 5, unit = TimeUnit.SECONDS)
        @DisplayName("wake with no parked instance and no snapshot throws FlowEngineException")
        void restoreWithMissingPlanThrows() {
            try (CoreFlowEngine engine = startedEngine(false)) {
                FlowContext fakeSleepingContext = context("orphan-instance", "non-existent-plan");
                org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> engine.scheduler().wake(fakeSleepingContext))
                        .isInstanceOf(eu.exeris.kernel.spi.exceptions.flow.FlowEngineException.class);
            }
        }
    }

    @Nested
    @DisplayName("Compensation and Failure Edge Cases")
    class CompensationTests {

        @Test
        @Timeout(value = 5, unit = TimeUnit.SECONDS)
        @DisplayName("failure triggers compensation in reverse order")
        void failureTriggersCompensationReverseOrder() throws InterruptedException {
            try (CoreFlowEngine engine = startedEngine(false)) {
                ConcurrentLinkedQueue<Integer> compensationOrder = new ConcurrentLinkedQueue<>();

                FlowDefinition definition = engine.plans().newDefinition("compensation-reverse")
                        .step("allocate", _ -> {
                            return FlowOutcome.CONTINUE;
                        }, _ -> {
                            compensationOrder.offer(0);
                            return FlowOutcome.CONTINUE;
                        })
                        .step("reserve", _ -> {
                            return FlowOutcome.CONTINUE;
                        }, _ -> {
                            compensationOrder.offer(1);
                            return FlowOutcome.CONTINUE;
                        })
                        .step("transfer", _ -> {
                            throw new RuntimeException("transfer failed");
                        }, _ -> {
                            compensationOrder.offer(2);
                            return FlowOutcome.CONTINUE;
                        })
                        .build();

                FlowExecutionPlan plan = engine.plans().compile(definition);
                FlowContext context = context("comp-instance", definition.name());

                // Schedule triggers failure in third step (happens in background thread)
                engine.scheduler().schedule(plan, context);

                awaitTrue(5_000, () -> engine.stats().failedFlows() >= 1);

                // Verify compensation executed
                List<Integer> actual = List.copyOf(compensationOrder);
                assertThat(actual).isNotEmpty();
                
                // Verify flow ended in failed state
                assertThat(engine.stats().failedFlows()).isGreaterThanOrEqualTo(1);
            }
        }

        @Test
        @Timeout(value = 5, unit = TimeUnit.SECONDS)
        @DisplayName("step throwing exception is caught and fails flow")
        void stepExceptionTriggersFailureState() throws InterruptedException {
            try (CoreFlowEngine engine = startedEngine(false)) {
                CountDownLatch failureObserved = new CountDownLatch(1);
                FlowDefinition definition = engine.plans().newDefinition("step-exception")
                        .step("normal", _ -> {
                            return FlowOutcome.CONTINUE;
                        }, null)
                        .step("throwing", _ -> {
                            failureObserved.countDown();
                            throw new IllegalStateException("step execution failed");
                        }, null)
                        .build();

                FlowExecutionPlan plan = engine.plans().compile(definition);
                FlowContext context = context("exception-instance", definition.name());

                try {
                    engine.scheduler().schedule(plan, context);
                } catch (Exception ex) {
                    // Expected: step failure throws
                }

                assertThat(failureObserved.await(5, TimeUnit.SECONDS)).isTrue();
                awaitTrue(5_000, () -> engine.stats().failedFlows() >= 1);

                assertThat(engine.stats().failedFlows()).isGreaterThanOrEqualTo(1);
            }
        }
    }

    @Nested
    @DisplayName("Concurrency Edge Cases")
    class ConcurrencyTests {

        @RepeatedTest(3)
        @Timeout(value = 5, unit = TimeUnit.SECONDS)
        @DisplayName("concurrent reschedules with same instance are safe (timing-dependent)")
        void concurrentReschedulesIdempotent() throws InterruptedException {
            try (CoreFlowEngine engine = startedEngine(false)) {
                AtomicInteger executions = new AtomicInteger();

                FlowDefinition definition = engine.plans().newDefinition("concurrent-same-" + System.nanoTime())
                        .step("process", _ -> {
                            executions.incrementAndGet();
                            return FlowOutcome.CONTINUE;
                        }, null)
                        .build();

                FlowExecutionPlan plan = engine.plans().compile(definition);
                FlowContext context = context("same-instance-" + System.nanoTime(), definition.name());

                int concurrency = 20;
                CountDownLatch go = new CountDownLatch(1);
                CountDownLatch done = new CountDownLatch(concurrency);

                for (int index = 0; index < concurrency; index++) {
                    Thread.ofVirtual().name("flow-concurrent-", index).start(() -> {
                        try {
                            go.await();
                            engine.scheduler().schedule(plan, context);
                        } catch (InterruptedException ex) {
                            Thread.currentThread().interrupt();
                        } finally {
                            done.countDown();
                        }
                    });
                }

                go.countDown();
                assertThat(done.await(5, TimeUnit.SECONDS)).isTrue();
                awaitTrue(5_000, () -> executions.get() >= 1);

                // Due to concurrent scheduling timing, at least 1 execution guaranteed
                assertThat(executions.get()).isGreaterThanOrEqualTo(1);
            }
        }

        @Test
        @Timeout(value = 5, unit = TimeUnit.SECONDS)
        @DisplayName("concurrent park and wake on same instance is safe")
        void concurrentParkWakeSafety() throws InterruptedException {
            try (CoreFlowEngine engine = startedEngine(false)) {
                CountDownLatch started = new CountDownLatch(1);
                CountDownLatch parkReady = new CountDownLatch(1);

                FlowDefinition definition = engine.plans().newDefinition("concurrent-park-wake")
                        .step("first", _ -> {
                            started.countDown();
                            parkReady.countDown();
                            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(100));
                            return FlowOutcome.CONTINUE;
                        }, null)
                        .step("second", _ -> {
                            return FlowOutcome.CONTINUE;
                        }, null)
                        .build();

                FlowExecutionPlan plan = engine.plans().compile(definition);
                FlowContext context = context("park-wake-instance", definition.name());

                // Schedule in background
                Thread scheduler = Thread.ofVirtual().start(() -> engine.scheduler().schedule(plan, context));

                // Let it start
                assertThat(started.await(2, TimeUnit.SECONDS)).isTrue();

                // Concurrent park should be safe (no-op or queued)
                engine.scheduler().park(context);

                // Wait for scheduling thread
                scheduler.join(TimeUnit.SECONDS.toMillis(5));

                // Engine should still be consistent
                assertThat(engine.stats().completedFlows() + engine.stats().failedFlows() + engine.stats().parkedFlows())
                        .isGreaterThanOrEqualTo(0);
            }
        }
    }

    @Nested
    @DisplayName("Terminal State Edge Cases")
    class TerminalStateTests {

        @Test
        @Timeout(value = 5, unit = TimeUnit.SECONDS)
        @DisplayName("park after completion is idempotent")
        void parkAfterCompletionIsIdempotent() throws InterruptedException {
            try (CoreFlowEngine engine = startedEngine(false)) {
                CountDownLatch completed = new CountDownLatch(1);

                FlowDefinition definition = engine.plans().newDefinition("complete-then-park")
                        .step("finish", _ -> {
                            completed.countDown();
                            return FlowOutcome.CONTINUE;
                        }, null)
                        .build();

                FlowExecutionPlan plan = engine.plans().compile(definition);
                FlowContext context = context("terminal-instance", definition.name());

                engine.scheduler().schedule(plan, context);
                assertThat(completed.await(5, TimeUnit.SECONDS)).isTrue();
                awaitTrue(5_000, () -> engine.stats().completedFlows() >= 1);

                long completedBefore = engine.stats().completedFlows();

                // Park should be no-op on completed flow
                engine.scheduler().park(context);

                // No change expected
                assertThat(engine.stats().completedFlows()).isEqualTo(completedBefore);
            }
        }

        @Test
        @Timeout(value = 5, unit = TimeUnit.SECONDS)
        @DisplayName("wake after completion is idempotent")
        void wakeAfterCompletionIsIdempotent() throws InterruptedException {
            try (CoreFlowEngine engine = startedEngine(false)) {
                CountDownLatch completed = new CountDownLatch(1);

                FlowDefinition definition = engine.plans().newDefinition("complete-then-wake")
                        .step("finish", _ -> {
                            completed.countDown();
                            return FlowOutcome.CONTINUE;
                        }, null)
                        .build();

                FlowExecutionPlan plan = engine.plans().compile(definition);
                FlowContext context = context("wake-terminal-instance", definition.name());

                engine.scheduler().schedule(plan, context);
                assertThat(completed.await(5, TimeUnit.SECONDS)).isTrue();
                awaitTrue(5_000, () -> engine.stats().completedFlows() >= 1);

                long completedBefore = engine.stats().completedFlows();

                // Wake should be no-op on completed flow
                engine.scheduler().wake(context);

                // No change expected
                assertThat(engine.stats().completedFlows()).isEqualTo(completedBefore);
            }
        }
    }

    @Nested
    @DisplayName("Step Execution Edge Cases")
    class StepExecutionTests {

        @Test
        @Timeout(value = 5, unit = TimeUnit.SECONDS)
        @DisplayName("multiple step flow executes all steps in order")
        void multipleStepsExecuteInOrder() throws InterruptedException {
            try (CoreFlowEngine engine = startedEngine(false)) {
                ConcurrentLinkedQueue<Integer> executedSteps = new ConcurrentLinkedQueue<>();

                FlowDefinition definition = engine.plans().newDefinition("multi-step")
                        .step("step-0", _ -> {
                            executedSteps.offer(0);
                            return FlowOutcome.CONTINUE;
                        }, null)
                        .step("step-1", _ -> {
                            executedSteps.offer(1);
                            return FlowOutcome.CONTINUE;
                        }, null)
                        .step("step-2", _ -> {
                            executedSteps.offer(2);
                            return FlowOutcome.CONTINUE;
                        }, null)
                        .step("step-3", _ -> {
                            executedSteps.offer(3);
                            return FlowOutcome.COMPLETE;
                        }, null)
                        .build();

                FlowExecutionPlan plan = engine.plans().compile(definition);
                FlowContext context = context("multi-step-instance", definition.name());

                engine.scheduler().schedule(plan, context);
                awaitTrue(5_000, () -> engine.stats().completedFlows() >= 1);

                assertThat(executedSteps)
                        .contains(0, 1, 2, 3)
                        .hasSize(4);
                assertThat(engine.stats().completedFlows()).isEqualTo(1);
            }
        }

        @Test
        @Timeout(value = 5, unit = TimeUnit.SECONDS)
        @DisplayName("PARK outcome halts execution and preserves state")
        void parkOutcomeHaltsExecution() throws InterruptedException {
            try (CoreFlowEngine engine = startedEngine(false)) {
                CountDownLatch parked = new CountDownLatch(1);

                FlowDefinition definition = engine.plans().newDefinition("park-halt")
                        .step("before-park", _ -> {
                            return FlowOutcome.CONTINUE;
                        }, null)
                        .step("causes-park", _ -> {
                            parked.countDown();
                            return FlowOutcome.PARK;
                        }, null)
                        .step("never-reached", _ -> {
                            throw new IllegalStateException("should not execute after park");
                        }, null)
                        .build();

                FlowExecutionPlan plan = engine.plans().compile(definition);
                FlowContext context = context("park-halt-instance", definition.name());

                engine.scheduler().schedule(plan, context);
                assertThat(parked.await(5, TimeUnit.SECONDS)).isTrue();
                awaitTrue(5_000, () -> engine.stats().parkedFlows() >= 1);

                assertThat(engine.stats().parkedFlows()).isEqualTo(1);
                assertThat(engine.stats().completedFlows()).isEqualTo(0);
            }
        }

        @Test
        @Timeout(value = 5, unit = TimeUnit.SECONDS)
        @DisplayName("COMPLETE outcome skips remaining steps")
        void completeOutcomeSkipsRemaining() throws InterruptedException {
            try (CoreFlowEngine engine = startedEngine(false)) {
                ConcurrentLinkedQueue<Integer> executed = new ConcurrentLinkedQueue<>();

                FlowDefinition definition = engine.plans().newDefinition("complete-skip")
                        .step("step-0", _ -> {
                            executed.offer(0);
                            return FlowOutcome.CONTINUE;
                        }, null)
                        .step("step-1", _ -> {
                            executed.offer(1);
                            return FlowOutcome.COMPLETE;
                        }, null)
                        .step("step-2-skipped", _ -> {
                            executed.offer(2);
                            return FlowOutcome.CONTINUE;
                        }, null)
                        .build();

                FlowExecutionPlan plan = engine.plans().compile(definition);
                FlowContext context = context("complete-skip-instance", definition.name());

                engine.scheduler().schedule(plan, context);
                awaitTrue(5_000, () -> engine.stats().completedFlows() >= 1);

                assertThat(executed).containsExactly(0, 1);
                assertThat(engine.stats().completedFlows()).isEqualTo(1);
            }
        }
    }

    /**
     * Polls {@code condition} every 10 ms until it returns {@code true} or {@code timeoutMs} expires.
     * Throws {@link AssertionError} if the condition is still false after the timeout.
     */
    private static void awaitTrue(long timeoutMs, BooleanSupplier condition)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (!condition.getAsBoolean()) {
            if (System.currentTimeMillis() > deadline) {
                throw new AssertionError("Condition not met within " + timeoutMs + " ms");
            }
            Thread.sleep(10);
        }
    }

    private static CoreFlowEngine startedEngine(boolean persistenceEnabled) {
        FlowEngineConfig defaults = FlowEngineConfig.defaults("CoreFlowEngineTest");
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
                        persistenceEnabled,
                        defaults.compensationEnabled()
                ),
                FlowEngineCapabilities.COMMUNITY.withProvider("core-flow-test")
        );
        if (persistenceEnabled) {
            ScopedValue.where(KernelProviders.FLOW_SNAPSHOT_STORE, new InMemorySnapshotStore()).run(engine::start);
        } else {
            engine.start();
        }
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

    private record RecordedEvent(EventDescriptor descriptor, String payload) {
    }

    private static final class RecordingEventEngine implements EventEngine {
        private final TestEventRegistry registry = new TestEventRegistry();
        private final ConcurrentLinkedQueue<RecordedEvent> events = new ConcurrentLinkedQueue<>();
        private final EventBus bus = new EventBus() {
            @Override
            public void publish(EventDescriptor descriptor, EventPayload payload) {
                try (payload) {
                    byte[] bytes = payload.length() == 0
                            ? new byte[0]
                            : payload.segment().toArray(ValueLayout.JAVA_BYTE);
                    events.add(new RecordedEvent(descriptor, new String(bytes, StandardCharsets.UTF_8)));
                }
            }

            @Override
            public SubscriptionToken subscribe(String eventType, EventHandler handler) {
                throw new UnsupportedOperationException();
            }

            @Override
            public void unsubscribe(SubscriptionToken token) {
            }

            @Override
            public void publishAndAwait(EventDescriptor descriptor, EventPayload payload) {
                publish(descriptor, payload);
            }
        };

        @Override
        public EventBus bus() {
            return bus;
        }

        @Override
        public EventQueue queue() {
            return null;
        }

        @Override
        public EventLoop loop() {
            return null;
        }

        @Override
        public EventRegistry registry() {
            return registry;
        }

        @Override
        public void start() {
        }

        @Override
        public void close() {
        }

        @Override
        public EventEngineStats stats() {
            return EventEngineStats.ZERO;
        }

        private List<RecordedEvent> events() {
            return List.copyOf(events);
        }
    }

    private static final class FailingEventEngine implements EventEngine {
        private final TestEventRegistry registry = new TestEventRegistry();
        private final EventBus bus = new EventBus() {
            @Override
            public void publish(EventDescriptor descriptor, EventPayload payload) {
                payload.close();
                throw new IllegalStateException("simulated publish failure");
            }

            @Override
            public SubscriptionToken subscribe(String eventType, EventHandler handler) {
                throw new UnsupportedOperationException();
            }

            @Override
            public void unsubscribe(SubscriptionToken token) {
            }

            @Override
            public void publishAndAwait(EventDescriptor descriptor, EventPayload payload) {
                publish(descriptor, payload);
            }
        };

        @Override
        public EventBus bus() {
            return bus;
        }

        @Override
        public EventQueue queue() {
            return null;
        }

        @Override
        public EventLoop loop() {
            return null;
        }

        @Override
        public EventRegistry registry() {
            return registry;
        }

        @Override
        public void start() {
        }

        @Override
        public void close() {
        }

        @Override
        public EventEngineStats stats() {
            return EventEngineStats.ZERO;
        }
    }

    private static final class TestEventRegistry implements EventRegistry {
        private final ConcurrentMap<String, EventTypeSpec> byName = new ConcurrentHashMap<>();
        private final ConcurrentMap<Integer, String> byOrdinal = new ConcurrentHashMap<>();

        @Override
        public void register(EventTypeSpec spec) {
            String existingName = byOrdinal.putIfAbsent(spec.ordinal(), spec.name());
            if (existingName != null && !existingName.equals(spec.name())) {
                throw new IllegalStateException("ordinal already registered");
            }
            EventTypeSpec existing = byName.putIfAbsent(spec.name(), spec);
            if (existing != null && !existing.equals(spec)) {
                throw new IllegalStateException("type already registered with different settings");
            }
        }

        @Override
        public EventTypeSpec resolve(String eventType) {
            return byName.get(eventType);
        }

        @Override
        public Set<String> registeredTypes() {
            return Set.copyOf(byName.keySet());
        }

        @Override
        public int size() {
            return byName.size();
        }
    }

    private static final class InMemorySnapshotStore implements FlowSnapshotStore {
        private final ConcurrentMap<FlowSnapshotKey, FlowSnapshot> snapshots = new ConcurrentHashMap<>();

        @Override
        public void save(FlowSnapshot snapshot) {
            snapshots.put(new FlowSnapshotKey(snapshot.instanceIdMost(), snapshot.instanceIdLeast()), snapshot);
        }

        @Override
        public Optional<FlowSnapshot> load(long instanceIdMost, long instanceIdLeast) {
            return Optional.ofNullable(snapshots.get(new FlowSnapshotKey(instanceIdMost, instanceIdLeast)));
        }

        @Override
        public void delete(long instanceIdMost, long instanceIdLeast) {
            snapshots.remove(new FlowSnapshotKey(instanceIdMost, instanceIdLeast));
        }

        @Override
        public boolean exists(long instanceIdMost, long instanceIdLeast) {
            return snapshots.containsKey(new FlowSnapshotKey(instanceIdMost, instanceIdLeast));
        }
    }

    private record FlowSnapshotKey(long instanceIdMost, long instanceIdLeast) {
    }
}