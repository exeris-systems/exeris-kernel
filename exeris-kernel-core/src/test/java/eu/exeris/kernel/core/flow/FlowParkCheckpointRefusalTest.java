/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.core.flow;

import eu.exeris.kernel.spi.context.KernelProviders;
import eu.exeris.kernel.spi.events.EventDescriptor;
import eu.exeris.kernel.spi.events.EventPayload;
import eu.exeris.kernel.spi.flow.ChoreographyDecision;
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
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What a refused PARK checkpoint does to the saga.
 *
 * <p>Before v0.12 the refusal escaped {@code runInstance} uncaught and killed the flow virtual
 * thread, while the instance had already been flipped to PARKED and registered - so it claimed a
 * durability it did not have. The obvious repair, flipping the state only after a successful
 * write, is worse: the instance is wakeable in this JVM, and refusing to park it turns a transient
 * store outage into a saga lost even without a restart.
 *
 * <p>So the park stands and the claim does not. One retry absorbs an instantaneous blip; past that
 * the instance is marked non-durable and counted in {@code FlowEngineShutdownEvent}, which is the
 * one thing an operator cannot infer from {@code parkedFlows} before restarting.
 */
@DisplayName("Flow: a refused PARK checkpoint keeps the saga, not the claim")
class FlowParkCheckpointRefusalTest {

    private static final String SHUTDOWN_EVENT = "eu.exeris.kernel.flow.Shutdown";
    private static final String ENGINE_NAME = "FlowParkCheckpointRefusalTest";

    @Test
    @Timeout(value = 20, unit = TimeUnit.SECONDS)
    @DisplayName("a saga whose PARK checkpoint is refused stays parked and still resumes")
    void refusedCheckpointKeepsTheSagaWakeable() throws InterruptedException {
        CountDownLatch parked = new CountDownLatch(1);
        CountDownLatch resumed = new CountDownLatch(1);
        RefusingStore store = new RefusingStore(Integer.MAX_VALUE);

        try (CoreFlowEngine engine = startedEngine(store)) {
            FlowContext context = scheduleParkingFlow(engine, "refused-but-wakeable", parked, resumed);

            assertThat(parked.await(5, TimeUnit.SECONDS)).isTrue();
            awaitParked(engine, context);

            assertThat(engine.scheduler()
                    .lookupParked(context.instanceIdMost(), context.instanceIdLeast()))
                    .as("a refused checkpoint must not cost the in-memory park: this instance is "
                        + "still the only copy of the saga and is still wakeable")
                    .isPresent();

            deliverWake(engine, context);

            assertThat(resumed.await(5, TimeUnit.SECONDS))
                    .as("the saga must resume; the store outage is not the saga's fault")
                    .isTrue();
        }

        assertThat(store.saveAttempts()).as("both attempts of the bounded retry were spent").isGreaterThanOrEqualTo(2);
    }

    @Test
    @Timeout(value = 20, unit = TimeUnit.SECONDS)
    @DisplayName("one refusal is absorbed by the retry and the checkpoint lands")
    void singleRefusalIsAbsorbedByTheRetry() throws InterruptedException {
        CountDownLatch parked = new CountDownLatch(1);
        RefusingStore store = new RefusingStore(1);

        try (CoreFlowEngine engine = startedEngine(store)) {
            FlowContext context = scheduleParkingFlow(engine, "refused-once", parked, new CountDownLatch(1));
            assertThat(parked.await(5, TimeUnit.SECONDS)).isTrue();
            // The parked-flow gauge rises at registration, which happens before the
            // checkpoint is attempted - so waiting on it would read the store too early.
            awaitAttempts(store, 2);

            assertThat(store.accepted())
                    .as("the retry must land the checkpoint the first attempt lost")
                    .isEqualTo(1);
            assertThat(store.saveAttempts())
                    .as("exactly one retry, not a loop")
                    .isEqualTo(2);
        }
    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    @DisplayName("shutdown reports how many parked sagas will not survive the restart")
    void shutdownCountsTheNonDurableParkedSagas() throws InterruptedException {
        CountDownLatch parked = new CountDownLatch(1);
        CountDownLatch eventReceived = new CountDownLatch(1);
        AtomicReference<RecordedEvent> captured = new AtomicReference<>();

        try (RecordingStream rs = new RecordingStream()) {
            rs.enable(SHUTDOWN_EVENT);
            // Other test classes close engines in this JVM; take only this one's event.
            rs.onEvent(SHUTDOWN_EVENT, event -> {
                if (!ENGINE_NAME.equals(event.getString("engineName"))) {
                    return;
                }
                captured.compareAndSet(null, event);
                eventReceived.countDown();
            });
            CountDownLatch streaming = new CountDownLatch(1);
            rs.onFlush(streaming::countDown);
            rs.startAsync();
            assertThat(streaming.await(10, TimeUnit.SECONDS)).isTrue();

            RefusingStore store = new RefusingStore(Integer.MAX_VALUE);
            try (CoreFlowEngine engine = startedEngine(store)) {
                scheduleParkingFlow(engine, "non-durable-park", parked, new CountDownLatch(1));
                assertThat(parked.await(5, TimeUnit.SECONDS)).isTrue();
                // Both attempts must be spent before close(), or the instance is not yet
                // marked non-durable and the count is taken too early.
                awaitAttempts(store, 2);
            }

            assertThat(eventReceived.await(15, TimeUnit.SECONDS)).isTrue();
        }

        // parkedFlows is the post-close stabilised counter view and reads 0 by existing
        // design; the new field is counted before close() clears the parked index, which
        // is the only moment the number still exists.
        assertThat(captured.get().getLong("nonDurableParkedFlows"))
                .as("the difference an operator cannot infer before restarting")
                .isEqualTo(1L);
    }

    private static FlowContext scheduleParkingFlow(CoreFlowEngine engine, String name,
                                                   CountDownLatch parked, CountDownLatch resumed) {
        FlowDefinition definition = engine.plans().newDefinition(name)
                .step("park-here", _ -> {
                    parked.countDown();
                    return FlowOutcome.PARK;
                }, null)
                .step("settle", _ -> {
                    resumed.countDown();
                    return FlowOutcome.COMPLETE;
                }, null)
                .build();
        FlowExecutionPlan plan = engine.plans().compile(definition);
        FlowContext context = context(name + "-instance", definition.name());
        engine.scheduler().schedule(plan, context);
        return context;
    }

    private static void awaitAttempts(RefusingStore store, int attempts)
            throws InterruptedException {
        for (int i = 0; i < 500 && store.saveAttempts() < attempts; i++) {
            TimeUnit.MILLISECONDS.sleep(10);
        }
        assertThat(store.saveAttempts()).isGreaterThanOrEqualTo(attempts);
    }

    /** The step latch fires before PARK is applied; the registration lands just after. */
    private static void awaitParked(CoreFlowEngine engine, FlowContext context)
            throws InterruptedException {
        for (int i = 0; i < 200 && engine.stats().parkedFlows() == 0; i++) {
            Thread.onSpinWait();
            TimeUnit.MILLISECONDS.sleep(10);
        }
        assertThat(engine.stats().parkedFlows()).isEqualTo(1L);
    }

    private static void deliverWake(CoreFlowEngine engine, FlowContext context) {
        new FlowChoreographyBridge(
                _ -> new ChoreographyDecision.Wake(context.instanceIdMost(), context.instanceIdLeast()),
                engine.scheduler())
                .handle(new EventDescriptor(1L, 2L, 3L, 4L, 0, 0, 0L), EventPayload.empty());
    }

    private static CoreFlowEngine startedEngine(FlowSnapshotStore store) {
        FlowEngineConfig defaults = FlowEngineConfig.defaults(ENGINE_NAME);
        FlowEngineConfig config = new FlowEngineConfig(
                defaults.engineName(), defaults.maxConcurrentFlows(), defaults.timeoutDurationNanos(),
                defaults.maxSteps(), defaults.maxTransitions(), defaults.maxExecutionPlans(),
                defaults.schedulerQueueCapacity(), defaults.partitionName(), defaults.partitionBytes(),
                true, defaults.compensationEnabled());
        CoreFlowEngine engine =
                new CoreFlowEngine(config, FlowEngineCapabilities.COMMUNITY.withProvider("test"));
        ScopedValue.where(KernelProviders.FLOW_SNAPSHOT_STORE, store).run(engine::start);
        return engine;
    }

    /** Refuses the first {@code refusals} saves, then behaves as an in-memory store. */
    private static final class RefusingStore implements FlowSnapshotStore {

        private final int refusals;
        private final AtomicInteger attempts = new AtomicInteger();
        private final AtomicInteger accepted = new AtomicInteger();
        private final Map<String, FlowSnapshot> rows = new ConcurrentHashMap<>();

        private RefusingStore(int refusals) {
            this.refusals = refusals;
        }

        int saveAttempts() {
            return attempts.get();
        }

        int accepted() {
            return accepted.get();
        }

        @Override
        public void save(FlowSnapshot snapshot) {
            if (attempts.incrementAndGet() <= refusals) {
                throw new IllegalStateException("no connection available for the park checkpoint");
            }
            accepted.incrementAndGet();
            rows.put(key(snapshot.instanceIdMost(), snapshot.instanceIdLeast()), snapshot);
        }

        @Override
        public Optional<FlowSnapshot> load(long instanceIdMost, long instanceIdLeast) {
            return Optional.ofNullable(rows.get(key(instanceIdMost, instanceIdLeast)));
        }

        @Override
        public void delete(long instanceIdMost, long instanceIdLeast) {
            rows.remove(key(instanceIdMost, instanceIdLeast));
        }

        @Override
        public boolean exists(long instanceIdMost, long instanceIdLeast) {
            return rows.containsKey(key(instanceIdMost, instanceIdLeast));
        }

        private static String key(long most, long least) {
            return most + ":" + least;
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
                return FlowState.CREATED;
            }

            @Override
            public long timeoutNanos() {
                return Long.MAX_VALUE;
            }
        };
    }
}
