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
                        } catch (InterruptedException ex) {
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
    @DisplayName("close emits the Flow shutdown JFR event required by the contract")
    void closeEmitsShutdownJfrEvent() throws Exception {
        CountDownLatch eventReceived = new CountDownLatch(1);
        AtomicReference<RecordedEvent> captured = new AtomicReference<>();

        try (RecordingStream rs = new RecordingStream()) {
            rs.enable(SHUTDOWN_EVENT);
            rs.onEvent(SHUTDOWN_EVENT, event -> {
                captured.compareAndSet(null, event);
                eventReceived.countDown();
            });
            rs.startAsync();

            try (CoreFlowEngine engine = startedEngine()) {
                assertThat(engine.stats().activeFlows()).isZero();
            }

            assertThat(eventReceived.await(3, TimeUnit.SECONDS))
                    .as("eu.exeris.kernel.flow.Shutdown must be emitted within 3 s of close()")
                    .isTrue();

            RecordedEvent event = captured.get();
            assertThat(event.getString("engineName")).isEqualTo("CoreFlowRuntimeTest");
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
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (!condition.getAsBoolean()) {
            if (System.currentTimeMillis() > deadline) {
                throw new AssertionError("Condition not met within " + timeoutMs + " ms");
            }
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(10));
        }
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
