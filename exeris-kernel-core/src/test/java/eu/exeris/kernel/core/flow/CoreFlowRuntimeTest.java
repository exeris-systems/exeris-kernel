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
import eu.exeris.kernel.spi.flow.FlowEngineCapabilities;
import eu.exeris.kernel.spi.flow.FlowEngineConfig;
import eu.exeris.kernel.spi.flow.model.FlowContext;
import eu.exeris.kernel.spi.flow.model.FlowDefinition;
import eu.exeris.kernel.spi.flow.model.FlowExecutionPlan;
import eu.exeris.kernel.spi.flow.model.FlowOutcome;
import eu.exeris.kernel.spi.flow.model.FlowSnapshot;
import eu.exeris.kernel.spi.flow.model.FlowSnapshotStore;
import eu.exeris.kernel.spi.flow.model.FlowState;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordingStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("CoreFlowRuntime - JFR telemetry")
class CoreFlowRuntimeTest {

    private static final String STEP_FAILED_EVENT = "eu.exeris.kernel.flow.StepFailed";
    private static final String SHUTDOWN_EVENT = "eu.exeris.kernel.flow.Shutdown";

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
            assertThat(event.getInt("stepIndex")).isZero();
            assertThat(event.getString("failureReason")).contains("step-exploded");
            assertThat(event.getLong("instanceIdMost") | event.getLong("instanceIdLeast"))
                    .isNotZero();
        }
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    @DisplayName("lookupParked suppresses repeated snapshot-store probes after a miss")
    void lookupParkedSuppressesRepeatedSnapshotStoreProbesAfterMiss() {
        CountingSnapshotStore snapshotStore = new CountingSnapshotStore();

        try (CoreFlowEngine engine = startedEngine(true, snapshotStore)) {
            Optional<FlowContext> first = engine.scheduler().lookupParked(11L, 22L);
            Optional<FlowContext> second = engine.scheduler().lookupParked(11L, 22L);
            Optional<FlowContext> third = engine.scheduler().lookupParked(11L, 22L);

            assertThat(first).isEmpty();
            assertThat(second).isEmpty();
            assertThat(third).isEmpty();
            assertThat(snapshotStore.loadCount())
                    .as("unknown parked-flow lookups should not keep re-probing the snapshot store")
                    .isEqualTo(1);
        }
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    @DisplayName("lookupParked returns empty instead of throwing when restart snapshot exists before the plan is recompiled")
    void lookupParkedFailsSoftWhenRestartSnapshotExistsBeforePlanRecompile() throws Exception {
        InMemorySnapshotStore snapshotStore = new InMemorySnapshotStore();
        CountDownLatch parked = new CountDownLatch(1);

        FlowContext parkedContext;

        try (CoreFlowEngine engine = startedEngine(true, snapshotStore)) {
            FlowDefinition definition = engine.plans().newDefinition("restart-plan-not-ready")
                    .step("park", _ -> {
                        parked.countDown();
                        return FlowOutcome.PARK;
                    }, null)
                    .build();

            FlowExecutionPlan plan = engine.plans().compile(definition);
            parkedContext = context("restart-plan-not-ready-instance", definition.name());

            engine.scheduler().schedule(plan, parkedContext);

            assertThat(parked.await(3, TimeUnit.SECONDS))
                    .as("flow must reach PARKED before restart")
                    .isTrue();
            awaitTrue(3_000, () -> snapshotStore.exists(
                    parkedContext.instanceIdMost(), parkedContext.instanceIdLeast()));
        }

        try (CoreFlowEngine rebuilt = startedEngine(true, snapshotStore)) {
            assertThat(rebuilt.scheduler().lookupParked(
                    parkedContext.instanceIdMost(), parkedContext.instanceIdLeast()))
                    .as("lookupParked should fail-soft until the plan is compiled again after restart")
                    .isEmpty();
        }
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    @DisplayName("plan-miss suppression is cleared once the flow plan is compiled again")
    void planMissSuppressionIsClearedOnceTheFlowPlanIsCompiledAgain() throws Exception {
        CountingSnapshotStore snapshotStore = new CountingSnapshotStore();
        CountDownLatch parked = new CountDownLatch(1);

        FlowContext parkedContext;
        FlowDefinition definition;

        try (CoreFlowEngine engine = startedEngine(true, snapshotStore)) {
            definition = engine.plans().newDefinition("plan-miss-clears-on-compile")
                    .step("park", _ -> {
                        parked.countDown();
                        return FlowOutcome.PARK;
                    }, null)
                    .build();

            FlowExecutionPlan plan = engine.plans().compile(definition);
            parkedContext = context("plan-miss-clears-on-compile-instance", definition.name());

            engine.scheduler().schedule(plan, parkedContext);

            assertThat(parked.await(3, TimeUnit.SECONDS))
                    .as("flow must reach PARKED before restart")
                    .isTrue();
            awaitTrue(3_000, () -> snapshotStore.exists(
                    parkedContext.instanceIdMost(), parkedContext.instanceIdLeast()));
        }

        int loadCountBeforeRestart = snapshotStore.loadCount();

        try (CoreFlowEngine rebuilt = startedEngine(true, snapshotStore)) {
            assertThat(rebuilt.scheduler().lookupParked(
                    parkedContext.instanceIdMost(), parkedContext.instanceIdLeast()))
                    .isEmpty();
            assertThat(rebuilt.scheduler().lookupParked(
                    parkedContext.instanceIdMost(), parkedContext.instanceIdLeast()))
                    .isEmpty();
            assertThat(snapshotStore.loadCount())
                    .as("plan-not-yet-available misses should be negatively suppressed after the first probe")
                    .isEqualTo(loadCountBeforeRestart + 1);

            rebuilt.plans().compile(definition);

            assertThat(rebuilt.scheduler().lookupParked(
                    parkedContext.instanceIdMost(), parkedContext.instanceIdLeast()))
                    .as("recompiling the plan must clear negative suppression so parked lookup can recover")
                    .isPresent();
            assertThat(snapshotStore.loadCount()).isEqualTo(loadCountBeforeRestart + 2);
        }
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    @DisplayName("lookupParked falls back to the snapshot store after restart")
    void lookupParkedFallsBackToSnapshotStoreAfterRestart() throws Exception {
        InMemorySnapshotStore snapshotStore = new InMemorySnapshotStore();
        CountDownLatch parked = new CountDownLatch(1);
        CountDownLatch resumed = new CountDownLatch(1);

        FlowContext parkedContext;
        FlowDefinition definition;

        try (CoreFlowEngine engine = startedEngine(true, snapshotStore)) {
            definition = engine.plans().newDefinition("restart-aware-lookup")
                    .step("park", _ -> {
                        parked.countDown();
                        return FlowOutcome.PARK;
                    }, null)
                    .step("resume", _ -> {
                        resumed.countDown();
                        return FlowOutcome.CONTINUE;
                    }, null)
                    .build();

            FlowExecutionPlan plan = engine.plans().compile(definition);
            parkedContext = context("restart-aware-instance", definition.name());

            engine.scheduler().schedule(plan, parkedContext);

            assertThat(parked.await(3, TimeUnit.SECONDS))
                    .as("flow must reach PARKED before restart")
                    .isTrue();
            awaitTrue(3_000, () -> snapshotStore.exists(
                    parkedContext.instanceIdMost(), parkedContext.instanceIdLeast()));
        }

        try (CoreFlowEngine rebuilt = startedEngine(true, snapshotStore)) {
            rebuilt.plans().compile(definition);

            Optional<FlowContext> restored = rebuilt.scheduler().lookupParked(
                    parkedContext.instanceIdMost(), parkedContext.instanceIdLeast());

            assertThat(restored)
                    .as("lookupParked must consult FlowSnapshotStore on in-memory miss after restart")
                    .isPresent();
            assertThat(restored.orElseThrow().state()).isEqualTo(FlowState.PARKED);

            rebuilt.scheduler().wake(restored.orElseThrow());
            assertThat(resumed.await(3, TimeUnit.SECONDS))
                    .as("the parked flow must resume after lookup-based wake on rebuilt runtime")
                    .isTrue();
        }
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    @DisplayName("rescheduling an already parked flow preserves its parked registration and count")
    void reschedulingAlreadyParkedFlowPreservesParkedRegistrationAndCount() throws Exception {
        InMemorySnapshotStore snapshotStore = new InMemorySnapshotStore();
        CountDownLatch parked = new CountDownLatch(1);
        CountDownLatch resumed = new CountDownLatch(1);

        try (CoreFlowEngine engine = startedEngine(true, snapshotStore)) {
            FlowDefinition definition = engine.plans().newDefinition("reschedule-keeps-parked-registration")
                    .step("park", _ -> {
                        parked.countDown();
                        return FlowOutcome.PARK;
                    }, null)
                    .step("resume", _ -> {
                        resumed.countDown();
                        return FlowOutcome.COMPLETE;
                    }, null)
                    .build();

            FlowExecutionPlan plan = engine.plans().compile(definition);
            FlowContext parkedContext = context("reschedule-keeps-parked-registration-instance", definition.name());

            engine.scheduler().schedule(plan, parkedContext);

            assertThat(parked.await(3, TimeUnit.SECONDS))
                    .as("flow must reach PARKED before reschedule")
                    .isTrue();
            awaitTrue(3_000, () -> engine.stats().parkedFlows() == 1L);

            engine.scheduler().schedule(plan, parkedContext);

            awaitTrue(3_000, () -> engine.scheduler().lookupParked(
                    parkedContext.instanceIdMost(), parkedContext.instanceIdLeast()).isPresent());
            assertThat(engine.stats().parkedFlows())
                    .as("a redundant schedule call must not unregister or uncount a parked flow")
                    .isEqualTo(1L);

            engine.scheduler().wake(engine.scheduler().lookupParked(
                    parkedContext.instanceIdMost(), parkedContext.instanceIdLeast()).orElseThrow());
            assertThat(resumed.await(3, TimeUnit.SECONDS))
                    .as("wake must still resume the parked flow immediately after the redundant schedule")
                    .isTrue();
        }
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    @DisplayName("clearing parked-lookup misses also clears stale miss-order entries")
    void clearingParkedLookupMissesAlsoClearsStaleMissOrderEntries() throws Exception {
        CountingSnapshotStore snapshotStore = new CountingSnapshotStore();

        try (CoreFlowEngine engine = startedEngine(true, snapshotStore)) {
            FlowDefinition definition = engine.plans().newDefinition("clears-stale-miss-order")
                    .step("complete", _ -> FlowOutcome.COMPLETE, null)
                    .build();
            FlowExecutionPlan plan = engine.plans().compile(definition);

            for (int i = 0; i < 400; i++) {
                long most = 10_000L + i;
                long least = 20_000L + i;

                assertThat(engine.scheduler().lookupParked(most, least)).isEmpty();
                engine.scheduler().schedule(plan, context(most, least, definition.name()));
            }

            awaitTrue(3_000, () -> engine.stats().completedFlows() == 400L);
            assertThat(parkedLookupMissOrderSize(engine))
                    .as("stale cleared keys must not accumulate indefinitely in the miss-order deque")
                    .isLessThanOrEqualTo(256);
        }
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    @DisplayName("restore-backed wake consumes parked registration before the resumed flow continues")
    void restoreBackedWakeConsumesParkedRegistration() throws Exception {
        InMemorySnapshotStore snapshotStore = new InMemorySnapshotStore();
        CountDownLatch parked = new CountDownLatch(1);
        CountDownLatch resumed = new CountDownLatch(1);
        CountDownLatch allowCompletion = new CountDownLatch(1);

        FlowContext parkedContext;
        FlowDefinition definition;

        try (CoreFlowEngine engine = startedEngine(true, snapshotStore)) {
            definition = engine.plans().newDefinition("restore-backed-wake-consumes-parked")
                    .step("park", _ -> {
                        parked.countDown();
                        return FlowOutcome.PARK;
                    }, null)
                    .step("resume", _ -> {
                        resumed.countDown();
                        try {
                            if (!allowCompletion.await(3, TimeUnit.SECONDS)) {
                                return FlowOutcome.FAIL;
                            }
                        } catch (InterruptedException _) {
                            Thread.currentThread().interrupt();
                            return FlowOutcome.FAIL;
                        }
                        return FlowOutcome.COMPLETE;
                    }, null)
                    .build();

            FlowExecutionPlan plan = engine.plans().compile(definition);
            parkedContext = context("restore-backed-wake-instance", definition.name());

            engine.scheduler().schedule(plan, parkedContext);

            assertThat(parked.await(3, TimeUnit.SECONDS))
                    .as("flow must reach PARKED before restart")
                    .isTrue();
            awaitTrue(3_000, () -> snapshotStore.exists(
                    parkedContext.instanceIdMost(), parkedContext.instanceIdLeast()));
        }

        try (CoreFlowEngine rebuilt = startedEngine(true, snapshotStore)) {
            rebuilt.plans().compile(definition);

            rebuilt.scheduler().wake(parkedContext);

            assertThat(resumed.await(3, TimeUnit.SECONDS))
                    .as("the restored flow must resume after wake")
                    .isTrue();

            assertThat(rebuilt.scheduler().lookupParked(
                    parkedContext.instanceIdMost(), parkedContext.instanceIdLeast()))
                    .as("restore-backed wake must consume parked registration before the flow is running")
                    .isEmpty();
            assertThat(rebuilt.stats().parkedFlows())
                    .as("parked flow count must drop to zero once wake resumes the flow")
                    .isZero();

            allowCompletion.countDown();
            awaitTrue(3_000, () -> rebuilt.stats().completedFlows() == 1L);
        }
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    @DisplayName("close emits the Flow shutdown JFR event with final counters after runtime quiesces")
    void closeEmitsShutdownJfrEvent() throws Exception {
        CountDownLatch eventReceived = new CountDownLatch(1);
        CountDownLatch stepStarted = new CountDownLatch(1);
        AtomicReference<RecordedEvent> captured = new AtomicReference<>();

        try (RecordingStream rs = new RecordingStream()) {
            rs.enable(SHUTDOWN_EVENT);
            rs.onEvent(SHUTDOWN_EVENT, event -> {
                captured.compareAndSet(null, event);
                eventReceived.countDown();
            });
            rs.startAsync();

            try (CoreFlowEngine engine = startedEngine()) {
                FlowDefinition definition = engine.plans().newDefinition("shutdown-final-stats")
                        .step("blocking-step", _ -> {
                            stepStarted.countDown();
                            try {
                                new CountDownLatch(1).await(5, TimeUnit.SECONDS);
                                return FlowOutcome.CONTINUE;
                            } catch (InterruptedException _) {
                                Thread.currentThread().interrupt();
                                return FlowOutcome.FAIL;
                            }
                        }, null)
                        .build();

                FlowExecutionPlan plan = engine.plans().compile(definition);
                FlowContext context = context("shutdown-final-stats-instance", definition.name());

                engine.scheduler().schedule(plan, context);

                assertThat(stepStarted.await(3, TimeUnit.SECONDS))
                        .as("flow must be running before close() to verify final shutdown counters")
                        .isTrue();
                awaitTrue(3_000, () -> engine.stats().activeFlows() == 1);
            }

            assertThat(eventReceived.await(3, TimeUnit.SECONDS))
                    .as("eu.exeris.kernel.flow.Shutdown must be emitted within 3 s of close()")
                    .isTrue();

            RecordedEvent event = captured.get();
            assertThat(event.getString("engineName")).isEqualTo("CoreFlowRuntimeTest");
            assertThat(event.getLong("activeFlows"))
                    .as("shutdown event must capture counters after runtime quiesces")
                    .isZero();
            assertThat(event.getLong("failedFlows")).isEqualTo(1L);
        }
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    @DisplayName("repeated close emits shutdown once and clears parked-flow counters")
    void repeatedCloseEmitsShutdownOnceAndClearsParkedFlowCounters() throws Exception {
        CountDownLatch eventReceived = new CountDownLatch(1);
        CountDownLatch parked = new CountDownLatch(1);
        AtomicInteger eventCount = new AtomicInteger();
        AtomicReference<RecordedEvent> captured = new AtomicReference<>();

        try (RecordingStream rs = new RecordingStream()) {
            rs.enable(SHUTDOWN_EVENT);
            rs.onEvent(SHUTDOWN_EVENT, event -> {
                eventCount.incrementAndGet();
                captured.compareAndSet(null, event);
                eventReceived.countDown();
            });
            rs.startAsync();

            CoreFlowEngine engine = startedEngine();
            try {
                FlowDefinition definition = engine.plans().newDefinition("shutdown-parked-stats")
                        .step("park", _ -> {
                            parked.countDown();
                            return FlowOutcome.PARK;
                        }, null)
                        .build();

                FlowExecutionPlan plan = engine.plans().compile(definition);
                FlowContext context = context("shutdown-parked-stats-instance", definition.name());

                engine.scheduler().schedule(plan, context);

                assertThat(parked.await(3, TimeUnit.SECONDS))
                        .as("flow must reach PARKED before close() to verify parked-flow shutdown counters")
                        .isTrue();
                awaitTrue(3_000, () -> engine.stats().parkedFlows() == 1L);

                engine.close();
                assertThat(engine.stats().parkedFlows())
                        .as("close() must leave parked-flow stats internally consistent with cleared runtime state")
                        .isZero();

                engine.close();
            } finally {
                engine.close();
            }

            assertThat(eventReceived.await(3, TimeUnit.SECONDS))
                    .as("eu.exeris.kernel.flow.Shutdown must be emitted when the engine is closed")
                    .isTrue();
            awaitTrue(3_000L, () -> eventCount.get() >= 1);

            assertThat(eventCount.get())
                    .as("repeated close() calls must not emit duplicate shutdown JFR events")
                    .isEqualTo(1);
            assertThat(captured.get().getLong("parkedFlows"))
                    .as("shutdown event must report zero parked flows after runtime teardown")
                    .isZero();
        }
    }

    @Test
    @Timeout(value = 15, unit = TimeUnit.SECONDS)
    @DisplayName("close keeps counters non-negative when a delayed worker exits after shutdown")
    void closeKeepsCountersNonNegativeWhenDelayedWorkerExitsAfterShutdown() throws Exception {
        CountDownLatch stepStarted = new CountDownLatch(1);
        CountDownLatch interruptObserved = new CountDownLatch(1);
        AtomicReference<Boolean> allowExit = new AtomicReference<>(false);

        CoreFlowEngine engine = startedEngine();
        try {
            FlowDefinition definition = engine.plans().newDefinition("shutdown-counter-clamp")
                    .step("linger-through-shutdown", _ -> {
                        stepStarted.countDown();
                        while (!Boolean.TRUE.equals(allowExit.get())) {
                            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(50));
                            if (Thread.interrupted()) {
                                interruptObserved.countDown();
                            }
                        }
                        return FlowOutcome.FAIL;
                    }, null)
                    .build();

            FlowExecutionPlan plan = engine.plans().compile(definition);
            FlowContext context = context("shutdown-counter-clamp-instance", definition.name());

            engine.scheduler().schedule(plan, context);

            assertThat(stepStarted.await(3, TimeUnit.SECONDS))
                    .as("flow must be running before close() to verify delayed shutdown accounting")
                    .isTrue();
            awaitTrue(3_000, () -> engine.stats().activeFlows() == 1L);

            engine.close();

            assertThat(interruptObserved.await(1, TimeUnit.SECONDS))
                    .as("close() must interrupt in-flight worker threads")
                    .isTrue();
            assertThat(engine.stats().activeFlows())
                    .as("close() should immediately report zero active flows after runtime teardown")
                    .isZero();
            assertThat(queueDepth(engine))
                    .as("close() should immediately reset queue depth after runtime teardown")
                    .isZero();
            long failedAfterClose = engine.stats().failedFlows();

            allowExit.set(true);
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(250));

            assertThat(engine.stats().failedFlows())
                    .as("late worker exits must not mutate the stable shutdown counters after close() returns")
                    .isEqualTo(failedAfterClose);
            assertThat(engine.stats().activeFlows())
                    .as("late worker teardown must not drive active flow counters below zero")
                    .isZero();
            assertThat(queueDepth(engine))
                    .as("late worker teardown must not drive queue depth below zero")
                    .isZero();
        } finally {
            allowExit.set(true);
            engine.close();
        }
    }

    private static CoreFlowEngine startedEngine() {
        return startedEngine(false, null);
    }

    private static CoreFlowEngine startedEngine(boolean persistenceEnabled, FlowSnapshotStore snapshotStore) {
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
                        persistenceEnabled,
                        defaults.compensationEnabled()
                ),
                FlowEngineCapabilities.COMMUNITY.withProvider("core-flow-runtime-test")
        );
        if (persistenceEnabled) {
            ScopedValue.where(KernelProviders.FLOW_SNAPSHOT_STORE, snapshotStore).run(engine::start);
        } else {
            engine.start();
        }
        return engine;
    }

    private static void awaitTrue(long timeoutMs, BooleanSupplier condition) {
        long timeoutNanos = TimeUnit.MILLISECONDS.toNanos(timeoutMs);
        long deadline = System.nanoTime() + timeoutNanos;
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("Condition not met within " + timeoutMs + " ms");
            }
            if (Thread.currentThread().isInterrupted()) {
                throw new AssertionError("awaitTrue interrupted while waiting");
            }
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(10));
            if (Thread.interrupted()) {
                Thread.currentThread().interrupt();
                throw new AssertionError("awaitTrue interrupted while waiting");
            }
        }
    }

    private static FlowContext context(String instanceId, String definitionName) {
        UUID uuid = UUID.nameUUIDFromBytes(instanceId.getBytes(StandardCharsets.UTF_8));
        return context(uuid.getMostSignificantBits(), uuid.getLeastSignificantBits(), definitionName);
    }

    private static FlowContext context(long instanceIdMost, long instanceIdLeast, String definitionName) {
        return new FlowContext() {
            @Override
            public long instanceIdMost() {
                return instanceIdMost;
            }

            @Override
            public long instanceIdLeast() {
                return instanceIdLeast;
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

    private static int parkedLookupMissOrderSize(CoreFlowEngine engine) {
        try {
            java.lang.reflect.Field runtimeField = CoreFlowEngine.class.getDeclaredField("runtime");
            runtimeField.setAccessible(true);
            Object runtime = runtimeField.get(engine);

            java.lang.reflect.Field orderField = CoreFlowRuntime.class.getDeclaredField("parkedLookupMissOrder");
            orderField.setAccessible(true);
            java.util.Deque<?> order = (java.util.Deque<?>) orderField.get(runtime);
            return order.size();
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("Unable to inspect parkedLookupMissOrder size", e);
        }
    }

    private static int queueDepth(CoreFlowEngine engine) {
        try {
            java.lang.reflect.Field runtimeField = CoreFlowEngine.class.getDeclaredField("runtime");
            runtimeField.setAccessible(true);
            Object runtime = runtimeField.get(engine);

            java.lang.reflect.Field queueDepthField = CoreFlowRuntime.class.getDeclaredField("queueDepth");
            queueDepthField.setAccessible(true);
            AtomicInteger queueDepth = (AtomicInteger) queueDepthField.get(runtime);
            return queueDepth.get();
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("Unable to inspect queue depth", e);
        }
    }

    private static class InMemorySnapshotStore implements FlowSnapshotStore {
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

    private static final class CountingSnapshotStore extends InMemorySnapshotStore {
        private final java.util.concurrent.atomic.AtomicInteger loadCount = new java.util.concurrent.atomic.AtomicInteger();

        @Override
        public Optional<FlowSnapshot> load(long instanceIdMost, long instanceIdLeast) {
            loadCount.incrementAndGet();
            return super.load(instanceIdMost, instanceIdLeast);
        }

        private int loadCount() {
            return loadCount.get();
        }
    }

    private record FlowSnapshotKey(long instanceIdMost, long instanceIdLeast) {
    }
}
