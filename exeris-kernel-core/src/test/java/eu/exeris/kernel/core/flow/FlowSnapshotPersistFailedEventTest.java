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
import java.time.Instant;
import java.util.Optional;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A refused durable checkpoint has to leave a machine-readable trace.
 *
 * <p>The failure this pins cost a day to diagnose from a benchmark run, because the only trace was
 * an uncaught exception on the flow virtual thread. The store-side event that exists for save
 * failures ({@code FlowSnapshotSaveFailedEvent}, JFR-091) is emitted from inside
 * {@code JdbcFlowSnapshotStore.save}'s try-with-resources body, so a failure raised by the resource
 * expression itself - the connection acquire - escapes every catch there and emits nothing. This
 * event sits at the call site instead, so it fires whatever the binding is and wherever inside it
 * the write died.
 *
 * <p>Behaviour is unchanged: the exception still propagates. Only the silence is removed.
 */
@DisplayName("Flow: a refused snapshot save is observable")
class FlowSnapshotPersistFailedEventTest {

    private static final String PERSIST_FAILED = "eu.exeris.kernel.flow.SnapshotPersistFailed";

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    @DisplayName("both save call sites emit SnapshotPersistFailed and still propagate")
    void bothSaveCallSitesAreObservable() throws InterruptedException {
        // One recording for both call sites. Two sequential RecordingStreams in one JVM shift
        // chunk boundaries under each other and drop events that a single stream sees.
        Queue<RecordedEvent> events = new ConcurrentLinkedQueue<>();
        CountDownLatch bothSeen = new CountDownLatch(2);

        try (RecordingStream rs = new RecordingStream()) {
            rs.enable(PERSIST_FAILED);
            rs.onEvent(PERSIST_FAILED, event -> {
                events.add(event);
                bothSeen.countDown();
            });
            // startAsync() returns before the stream is processing, and the first write below
            // is immediate - without this the event is emitted into a stream that has not
            // started and is lost. onFlush is the first point at which it demonstrably has.
            CountDownLatch streaming = new CountDownLatch(1);
            rs.onFlush(streaming::countDown);
            rs.startAsync();
            assertThat(streaming.await(10, TimeUnit.SECONDS))
                    .as("the recording stream must be live before any event is emitted")
                    .isTrue();

            // Call site 1: the migration-on-load write, which carries a snapshot rather than a
            // live instance. Asserted through the writer directly - building a real version
            // migration would test the migration machinery, not whether this write is observable.
            FlowSnapshot migrated = new FlowSnapshot(
                    7L, 9L, "migrated-definition", 3, FlowState.PARKED,
                    Instant.EPOCH, Instant.MAX, new int[0], 0, new byte[0], 1L);
            assertThatThrownBy(() -> FlowSnapshotWriter.save(new RefusingSnapshotStore(), migrated))
                    .as("behaviour is unchanged: the refusal still propagates")
                    .isInstanceOf(IllegalStateException.class);

            // Call site 2: the per-step PARK checkpoint, driven through a real engine. The
            // engine stays open until both events land - closing it while the step is still
            // returning would let the lifecycle write-fence skip the save under test.
            assertThat(runParkThatCannotPersist(bothSeen))
                    .as("a refused checkpoint must not be silent, at either call site")
                    .isTrue();
        }

        assertThat(events).extracting(e -> e.getString("definitionName"))
                .contains("migrated-definition", "persist-refusing-park");
        assertThat(events).allSatisfy(event -> {
            assertThat(event.getString("state")).isEqualTo(FlowState.PARKED.name());
            assertThat(event.getString("failureReason"))
                    .as("type and message, no stack trace and no business payload")
                    .contains(IllegalStateException.class.getName());
        });
    }

    private static boolean runParkThatCannotPersist(CountDownLatch bothSeen)
            throws InterruptedException {
        CountDownLatch stepEntered = new CountDownLatch(1);
        FlowEngineConfig defaults = FlowEngineConfig.defaults("FlowSnapshotPersistFailedEventTest");
        FlowEngineConfig config = new FlowEngineConfig(
                defaults.engineName(), defaults.maxConcurrentFlows(), defaults.timeoutDurationNanos(),
                defaults.maxSteps(), defaults.maxTransitions(), defaults.maxExecutionPlans(),
                defaults.schedulerQueueCapacity(), defaults.partitionName(), defaults.partitionBytes(),
                true, defaults.compensationEnabled());

        try (CoreFlowEngine engine =
                     new CoreFlowEngine(config, FlowEngineCapabilities.COMMUNITY.withProvider("test"))) {
            ScopedValue.where(KernelProviders.FLOW_SNAPSHOT_STORE, new RefusingSnapshotStore())
                    .run(engine::start);

            FlowDefinition definition = engine.plans().newDefinition("persist-refusing-park")
                    .step("park-here", _ -> {
                        stepEntered.countDown();
                        return FlowOutcome.PARK;
                    }, null)
                    .build();

            FlowExecutionPlan plan = engine.plans().compile(definition);
            engine.scheduler().schedule(plan, context("persist-refusing-instance", definition.name()));
            assertThat(stepEntered.await(5, TimeUnit.SECONDS)).isTrue();
            return bothSeen.await(20, TimeUnit.SECONDS);
        }
    }

    /** Refuses every write, the way an exhausted pool refuses the acquire the write needs. */
    private static final class RefusingSnapshotStore implements FlowSnapshotStore {

        @Override
        public void save(FlowSnapshot snapshot) {
            throw new IllegalStateException("no connection available for the park checkpoint");
        }

        @Override
        public Optional<FlowSnapshot> load(long instanceIdMost, long instanceIdLeast) {
            return Optional.empty();
        }

        @Override
        public void delete(long instanceIdMost, long instanceIdLeast) {
            // nothing persisted, nothing to remove
        }

        @Override
        public boolean exists(long instanceIdMost, long instanceIdLeast) {
            return false;
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
