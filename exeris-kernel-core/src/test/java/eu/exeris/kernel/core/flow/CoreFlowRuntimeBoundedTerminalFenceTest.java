/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.core.flow;

import eu.exeris.kernel.core.flow.CoreFlowRuntime.ScheduleOutcome;
import eu.exeris.kernel.spi.context.KernelProviders;
import eu.exeris.kernel.spi.exceptions.flow.FlowEngineException;
import eu.exeris.kernel.spi.flow.FlowEngineCapabilities;
import eu.exeris.kernel.spi.flow.FlowEngineConfig;
import eu.exeris.kernel.spi.flow.model.FlowContext;
import eu.exeris.kernel.spi.flow.model.FlowDefinition;
import eu.exeris.kernel.spi.flow.model.FlowExecutionPlan;
import eu.exeris.kernel.spi.flow.model.FlowOutcome;
import eu.exeris.kernel.spi.flow.model.FlowSnapshot;
import eu.exeris.kernel.spi.flow.model.FlowSnapshotStore;
import eu.exeris.kernel.spi.flow.model.FlowState;
import eu.exeris.kernel.tck.contract.flow.VersionEnforcingSnapshotStore;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordingStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.Queue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A completed flow stays completed once the bounded terminal catalog no longer remembers it.
 *
 * <p>The in-memory catalog is the fence against a redelivered start. Under a bound it forgets the
 * least recently used entry, and a restart forgets all of them; what remains is the durable row.
 * The engine must therefore leave a row that says the instance finished, whatever the instance did
 * before it finished. Each case ends the previous run by joining the worker thread that executed the
 * last step, so a completion's store write has happened before the case looks at the store.
 */
@DisplayName("Flow: a bounded terminal catalog does not reopen a finished instance")
class CoreFlowRuntimeBoundedTerminalFenceTest {

    private static final long AWAIT_SECONDS = 5L;
    private static final long NEGATIVE_WAIT_MILLIS = 300L;
    private static final int BOUND = 1;
    private static final int UNBOUNDED = 0;

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    @DisplayName("a flow that never parked is absorbed as terminal after its catalog entry is evicted")
    void neverParkedCompletionIsAbsorbedAfterEviction() throws InterruptedException {
        SignallingStore store = new SignallingStore();
        AtomicInteger runs = new AtomicInteger();
        CountDownLatch rerun = new CountDownLatch(1);
        StepProbe probe = new StepProbe();

        try (CoreFlowEngine engine = startedEngine(store, BOUND, true)) {
            FlowExecutionPlan plan = engine.plans().compile(singleStep(engine, "never-parked", runs, rerun, probe));
            FlowContext ctx = context("never-parked-instance", "never-parked");

            assertThat(engine.runtime().submitStart(plan, ctx)).isEqualTo(ScheduleOutcome.STARTED);
            probe.awaitExit();
            assertThat(runs.get()).isEqualTo(1);
            evict(engine);
            assertThat(engine.runtime().existingState(FlowKey.from(ctx), ScheduleOutcome.TERMINAL))
                    .as("the entry is evicted before the resubmit").isEqualTo("TERMINAL");

            ScheduleOutcome outcome = engine.runtime().submitStart(plan, ctx);

            assertThat(outcome).isEqualTo(ScheduleOutcome.TERMINAL);
            assertThat(engine.runtime().existingState(FlowKey.from(ctx), outcome)).isEqualTo("COMPLETED");
            assertThat(rerun.await(NEGATIVE_WAIT_MILLIS, TimeUnit.MILLISECONDS)).isFalse();
            assertThat(runs.get()).isEqualTo(1);
            assertThat(store.versions.conflicts()).isZero();
        }
    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    @DisplayName("a flow that parked and then completed is absorbed, not resurrected as parked, after eviction")
    void parkedThenCompletedIsAbsorbedNotResurrectedAfterEviction() throws InterruptedException {
        SignallingStore store = new SignallingStore();
        AtomicInteger runs = new AtomicInteger();
        CountDownLatch rerun = new CountDownLatch(1);
        StepProbe waitProbe = new StepProbe();
        StepProbe finishProbe = new StepProbe();

        try (CoreFlowEngine engine = startedEngine(store, BOUND, true)) {
            FlowExecutionPlan plan = engine.plans().compile(
                    parkThenFinish(engine, "parked-completed", runs, rerun, waitProbe, finishProbe));
            FlowContext ctx = context("parked-completed-instance", "parked-completed");
            FlowKey key = FlowKey.from(ctx);

            assertThat(engine.runtime().submitStart(plan, ctx)).isEqualTo(ScheduleOutcome.STARTED);
            store.awaitSave(key, FlowState.PARKED);
            waitProbe.awaitExit();
            engine.scheduler().wake(ctx.instanceIdMost(), ctx.instanceIdLeast());
            finishProbe.awaitExit();
            assertThat(runs.get()).isEqualTo(1);
            evict(engine);
            assertThat(engine.runtime().existingState(key, ScheduleOutcome.TERMINAL)).isEqualTo("TERMINAL");

            ScheduleOutcome outcome = engine.runtime().submitStart(plan, ctx);

            assertThat(outcome).isEqualTo(ScheduleOutcome.TERMINAL);
            assertThat(engine.runtime().existingState(key, outcome)).isEqualTo("COMPLETED");
            assertThat(engine.scheduler().lookupParked(ctx.instanceIdMost(), ctx.instanceIdLeast())).isEmpty();
            assertThat(engine.stats().parkedFlows()).isZero();
            assertThat(rerun.await(NEGATIVE_WAIT_MILLIS, TimeUnit.MILLISECONDS)).isFalse();
            assertThat(runs.get()).isEqualTo(1);
            assertThat(store.versions.conflicts()).isZero();
        }
    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    @DisplayName("a wake after eviction is refused and does not run the steps after the park again")
    void wakeAfterEvictionDoesNotRerunTheStepsAfterThePark() throws InterruptedException {
        SignallingStore store = new SignallingStore();
        AtomicInteger runs = new AtomicInteger();
        CountDownLatch rerun = new CountDownLatch(1);
        StepProbe waitProbe = new StepProbe();
        StepProbe finishProbe = new StepProbe();

        try (CoreFlowEngine engine = startedEngine(store, BOUND, true)) {
            FlowExecutionPlan plan = engine.plans().compile(
                    parkThenFinish(engine, "wake-after-eviction", runs, rerun, waitProbe, finishProbe));
            FlowContext ctx = context("wake-after-eviction-instance", "wake-after-eviction");
            FlowKey key = FlowKey.from(ctx);

            assertThat(engine.runtime().submitStart(plan, ctx)).isEqualTo(ScheduleOutcome.STARTED);
            store.awaitSave(key, FlowState.PARKED);
            waitProbe.awaitExit();
            engine.scheduler().wake(ctx.instanceIdMost(), ctx.instanceIdLeast());
            finishProbe.awaitExit();
            evict(engine);
            assertThat(engine.runtime().existingState(key, ScheduleOutcome.TERMINAL)).isEqualTo("TERMINAL");

            assertThat(engine.scheduler().lookupParked(ctx.instanceIdMost(), ctx.instanceIdLeast())).isEmpty();
            assertThatThrownBy(() -> engine.scheduler().wake(ctx.instanceIdMost(), ctx.instanceIdLeast()))
                    .matches(FlowEngineException::isNotParked);

            assertThat(rerun.await(NEGATIVE_WAIT_MILLIS, TimeUnit.MILLISECONDS)).isFalse();
            assertThat(runs.get()).isEqualTo(1);
        }
    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    @DisplayName("a compensated failure stays terminal after eviction")
    void compensatedFailureStaysTerminalAfterEviction() throws InterruptedException {
        assertFailureStaysTerminal(true);
    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    @DisplayName("a failure with compensation disabled stays terminal after eviction")
    void failureWithCompensationDisabledStaysTerminalAfterEviction() throws InterruptedException {
        assertFailureStaysTerminal(false);
    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    @DisplayName("a flow that never parked is absorbed as terminal by a restarted engine on the same store")
    void neverParkedCompletionIsAbsorbedAfterRestart() throws InterruptedException {
        SignallingStore store = new SignallingStore();
        AtomicInteger runs = new AtomicInteger();
        CountDownLatch rerun = new CountDownLatch(1);
        StepProbe probe = new StepProbe();
        FlowContext ctx = context("restart-instance", "restart-flow");

        try (CoreFlowEngine first = startedEngine(store, BOUND, true)) {
            FlowExecutionPlan plan = first.plans().compile(singleStep(first, "restart-flow", runs, rerun, probe));
            assertThat(first.runtime().submitStart(plan, ctx)).isEqualTo(ScheduleOutcome.STARTED);
            probe.awaitExit();
            assertThat(runs.get()).isEqualTo(1);
        }

        try (CoreFlowEngine second = startedEngine(store, BOUND, true)) {
            FlowExecutionPlan plan = second.plans().compile(
                    singleStep(second, "restart-flow", runs, rerun, new StepProbe()));

            ScheduleOutcome outcome = second.runtime().submitStart(plan, ctx);

            assertThat(outcome).isEqualTo(ScheduleOutcome.TERMINAL);
            assertThat(rerun.await(NEGATIVE_WAIT_MILLIS, TimeUnit.MILLISECONDS)).isFalse();
            assertThat(runs.get()).isEqualTo(1);
        }
    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    @DisplayName("an unbounded catalog still reclaims the row and writes no terminal row")
    void unboundedCatalogStillReclaimsTheRowAndWritesNoTerminalRow() throws InterruptedException {
        SignallingStore store = new SignallingStore();
        AtomicInteger runs = new AtomicInteger();
        CountDownLatch rerun = new CountDownLatch(1);
        StepProbe probe = new StepProbe();
        StepProbe waitProbe = new StepProbe();
        StepProbe finishProbe = new StepProbe();

        try (CoreFlowEngine engine = startedEngine(store, UNBOUNDED, true)) {
            FlowExecutionPlan never = engine.plans().compile(singleStep(engine, "unbounded-never", runs, rerun, probe));
            FlowContext neverCtx = context("unbounded-never-instance", "unbounded-never");
            FlowExecutionPlan parked = engine.plans().compile(
                    parkThenFinish(engine, "unbounded-parked", runs, rerun, waitProbe, finishProbe));
            FlowContext parkedCtx = context("unbounded-parked-instance", "unbounded-parked");

            engine.runtime().submitStart(never, neverCtx);
            probe.awaitExit();
            engine.runtime().submitStart(parked, parkedCtx);
            store.awaitSave(FlowKey.from(parkedCtx), FlowState.PARKED);
            waitProbe.awaitExit();
            engine.scheduler().wake(parkedCtx.instanceIdMost(), parkedCtx.instanceIdLeast());
            finishProbe.awaitExit();

            assertThat(store.versions.load(neverCtx.instanceIdMost(), neverCtx.instanceIdLeast())).isEmpty();
            assertThat(store.versions.load(parkedCtx.instanceIdMost(), parkedCtx.instanceIdLeast())).isEmpty();
            assertThat(store.log).noneMatch(s -> s.state() == FlowState.COMPLETED);
        }
    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    @DisplayName("a bounded completion replaces the row with a completed snapshot")
    void boundedCompletionReplacesTheRowWithACompletedSnapshot() throws InterruptedException {
        SignallingStore store = new SignallingStore();
        AtomicInteger runs = new AtomicInteger();
        CountDownLatch rerun = new CountDownLatch(1);
        StepProbe probe = new StepProbe();
        StepProbe waitProbe = new StepProbe();
        StepProbe finishProbe = new StepProbe();

        try (CoreFlowEngine engine = startedEngine(store, BOUND, true)) {
            FlowDefinition neverDef = singleStep(engine, "row-never", runs, rerun, probe);
            FlowExecutionPlan never = engine.plans().compile(neverDef);
            FlowContext neverCtx = context("row-never-instance", "row-never");
            FlowDefinition parkedDef = parkThenFinish(engine, "row-parked", runs, rerun, waitProbe, finishProbe);
            FlowExecutionPlan parked = engine.plans().compile(parkedDef);
            FlowContext parkedCtx = context("row-parked-instance", "row-parked");

            engine.runtime().submitStart(never, neverCtx);
            probe.awaitExit();
            Optional<FlowSnapshot> neverRow =
                    store.versions.load(neverCtx.instanceIdMost(), neverCtx.instanceIdLeast());
            assertThat(neverRow).isPresent();
            assertThat(neverRow.get().state()).isEqualTo(FlowState.COMPLETED);
            assertThat(neverRow.get().definitionName()).isEqualTo(never.definitionName());
            assertThat(neverRow.get().definitionVersion()).isEqualTo(never.definitionVersion());

            engine.runtime().submitStart(parked, parkedCtx);
            store.awaitSave(FlowKey.from(parkedCtx), FlowState.PARKED);
            waitProbe.awaitExit();
            long parkedVersion = store.versions
                    .load(parkedCtx.instanceIdMost(), parkedCtx.instanceIdLeast()).orElseThrow().schemaVersion();
            engine.scheduler().wake(parkedCtx.instanceIdMost(), parkedCtx.instanceIdLeast());
            finishProbe.awaitExit();
            Optional<FlowSnapshot> parkedRow =
                    store.versions.load(parkedCtx.instanceIdMost(), parkedCtx.instanceIdLeast());
            assertThat(parkedRow).isPresent();
            assertThat(parkedRow.get().state()).isEqualTo(FlowState.COMPLETED);
            assertThat(parkedRow.get().schemaVersion()).isEqualTo(parkedVersion + 1);
            assertThat(store.versions.conflicts()).isZero();
        }
    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    @DisplayName("a wake that meets an instance still writing its completed row records it as terminal")
    void wakeDuringTheCompletedWriteRecordsTheTerminalState() throws InterruptedException {
        SignallingStore store = new SignallingStore();
        AtomicInteger runs = new AtomicInteger();
        CountDownLatch rerun = new CountDownLatch(1);
        StepProbe waitProbe = new StepProbe();
        StepProbe finishProbe = new StepProbe();

        try (CoreFlowEngine engine = startedEngine(store, BOUND, true)) {
            FlowExecutionPlan plan = engine.plans().compile(
                    parkThenFinish(engine, "wake-during-write", runs, rerun, waitProbe, finishProbe));
            FlowContext ctx = context("wake-during-write-instance", "wake-during-write");
            FlowKey key = FlowKey.from(ctx);
            engine.runtime().submitStart(plan, ctx);
            store.awaitSave(key, FlowState.PARKED);
            waitProbe.awaitExit();
            CountDownLatch entered = store.holdCompletedSave();

            engine.scheduler().wake(ctx.instanceIdMost(), ctx.instanceIdLeast());
            assertThat(entered.await(AWAIT_SECONDS, TimeUnit.SECONDS)).as("completed write started").isTrue();
            engine.scheduler().wake(ctx.instanceIdMost(), ctx.instanceIdLeast());

            assertThat(engine.runtime().existingState(key, ScheduleOutcome.TERMINAL)).isEqualTo("COMPLETED");
            store.releaseCompletedSave();
            finishProbe.awaitExit();
            assertThat(engine.runtime().isLive(key)).isFalse();
            assertThat(runs.get()).isEqualTo(1);
        }
    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    @DisplayName("a refused completed row leaves the completion standing, reported and out of the live index")
    void refusedCompletedRowLeavesTheCompletionStanding() throws InterruptedException {
        String event = "eu.exeris.kernel.flow.SnapshotPersistFailed";
        SignallingStore store = new SignallingStore();
        store.refuseCompletedSaves();
        AtomicInteger runs = new AtomicInteger();
        CountDownLatch rerun = new CountDownLatch(1);
        StepProbe probe = new StepProbe();
        Queue<RecordedEvent> events = new ConcurrentLinkedQueue<>();
        CountDownLatch seen = new CountDownLatch(1);

        try (RecordingStream rs = new RecordingStream();
             CoreFlowEngine engine = startedEngine(store, BOUND, true)) {
            rs.enable(event);
            rs.onEvent(event, e -> {
                events.add(e);
                seen.countDown();
            });
            CountDownLatch streaming = new CountDownLatch(1);
            rs.onFlush(streaming::countDown);
            rs.startAsync();
            assertThat(streaming.await(10, TimeUnit.SECONDS)).as("recording is live").isTrue();

            FlowExecutionPlan plan = engine.plans().compile(
                    singleStep(engine, "refused-completed-row", runs, rerun, probe));
            FlowContext ctx = context("refused-completed-row-instance", "refused-completed-row");
            FlowKey key = FlowKey.from(ctx);
            engine.runtime().submitStart(plan, ctx);
            probe.awaitExit();

            assertThat(engine.runtime().existingState(key, ScheduleOutcome.TERMINAL)).isEqualTo("COMPLETED");
            assertThat(engine.stats().completedFlows()).isEqualTo(1);
            assertThat(engine.runtime().isLive(key)).isFalse();
            assertThat(seen.await(10, TimeUnit.SECONDS)).as("persist-failed event").isTrue();
            assertThat(events).anySatisfy(e -> assertThat(e.getString("state")).isEqualTo("COMPLETED"));
        }
    }

    private static void assertFailureStaysTerminal(boolean compensation) throws InterruptedException {
        SignallingStore store = new SignallingStore();
        AtomicInteger reserveRuns = new AtomicInteger();
        AtomicInteger compensations = new AtomicInteger();
        CountDownLatch rerun = new CountDownLatch(1);
        StepProbe probe = new StepProbe();

        try (CoreFlowEngine engine = startedEngine(store, BOUND, compensation)) {
            FlowDefinition definition = engine.plans().newDefinition("failing-" + compensation)
                    .step("reserve", _ -> {
                        if (reserveRuns.incrementAndGet() > 1) {
                            rerun.countDown();
                        }
                        return FlowOutcome.CONTINUE;
                    }, _ -> {
                        compensations.incrementAndGet();
                        return FlowOutcome.CONTINUE;
                    })
                    .step("charge", _ -> {
                        probe.record();
                        return FlowOutcome.FAIL;
                    }, null)
                    .build();
            FlowExecutionPlan plan = engine.plans().compile(definition);
            FlowContext ctx = context("failing-instance-" + compensation, definition.name());
            FlowKey key = FlowKey.from(ctx);

            assertThat(engine.runtime().submitStart(plan, ctx)).isEqualTo(ScheduleOutcome.STARTED);
            probe.awaitExit();
            int expectedCompensations = compensation ? 1 : 0;
            assertThat(compensations.get()).isEqualTo(expectedCompensations);
            assertThat(store.versions.load(ctx.instanceIdMost(), ctx.instanceIdLeast()))
                    .hasValueSatisfying(row -> assertThat(row.state()).isEqualTo(FlowState.FAILED_ROLLEDBACK));
            evict(engine);
            assertThat(engine.runtime().existingState(key, ScheduleOutcome.TERMINAL)).isEqualTo("TERMINAL");

            ScheduleOutcome outcome = engine.runtime().submitStart(plan, ctx);

            assertThat(outcome).isEqualTo(ScheduleOutcome.TERMINAL);
            assertThat(engine.runtime().existingState(key, outcome)).isEqualTo("FAILED_ROLLEDBACK");
            assertThat(rerun.await(NEGATIVE_WAIT_MILLIS, TimeUnit.MILLISECONDS)).isFalse();
            assertThat(reserveRuns.get()).isEqualTo(1);
            assertThat(compensations.get()).isEqualTo(expectedCompensations);
        }
    }

    /** Completes a filler flow under a fresh key, which pushes the least recently used entry out. */
    private static void evict(CoreFlowEngine engine) throws InterruptedException {
        StepProbe probe = new StepProbe();
        String name = "filler-" + UUID.randomUUID();
        FlowDefinition definition = engine.plans().newDefinition(name)
                .step("only", _ -> {
                    probe.record();
                    return FlowOutcome.CONTINUE;
                }, null)
                .build();
        engine.runtime().submitStart(engine.plans().compile(definition), context(name + "-instance", name));
        probe.awaitExit();
    }

    private static FlowDefinition singleStep(CoreFlowEngine engine, String name, AtomicInteger runs, CountDownLatch rerun,
                                             StepProbe probe) {
        return engine.plans().newDefinition(name)
                .step("only", _ -> {
                    countRun(runs, rerun);
                    probe.record();
                    return FlowOutcome.CONTINUE;
                }, null)
                .build();
    }

    private static FlowDefinition parkThenFinish(CoreFlowEngine engine, String name, AtomicInteger runs, CountDownLatch rerun,
                                                 StepProbe waitProbe, StepProbe finishProbe) {
        return engine.plans().newDefinition(name)
                .step("wait", _ -> {
                    waitProbe.record();
                    return FlowOutcome.PARK;
                }, null)
                .step("finish", _ -> {
                    countRun(runs, rerun);
                    finishProbe.record();
                    return FlowOutcome.CONTINUE;
                }, null)
                .build();
    }

    private static void countRun(AtomicInteger runs, CountDownLatch rerun) {
        if (runs.incrementAndGet() > 1) {
            rerun.countDown();
        }
    }

    private static CoreFlowEngine startedEngine(SignallingStore store, int catalogMax, boolean compensation) {
        FlowEngineConfig d = FlowEngineConfig.defaults("CoreFlowRuntimeBoundedTerminalFenceTest");
        FlowEngineConfig config = new FlowEngineConfig(
                d.engineName(), d.maxConcurrentFlows(), d.timeoutDurationNanos(), d.maxSteps(),
                d.maxTransitions(), d.maxExecutionPlans(), d.schedulerQueueCapacity(), d.partitionName(),
                d.partitionBytes(), true, compensation, catalogMax,
                d.defaultSagaTimeoutShortNanos(), d.defaultSagaTimeoutLongNanos());
        CoreFlowEngine engine = new CoreFlowEngine(
                config, FlowEngineCapabilities.COMMUNITY.withProvider("fence-test"));
        ScopedValue.where(KernelProviders.FLOW_SNAPSHOT_STORE, store).run(engine::start);
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

    /**
     * Records the worker thread that ran a step. The worker ends only after the run's bookkeeping,
     * including any store write the transition makes, so joining it is the completion barrier.
     */
    private static final class StepProbe {

        private final AtomicReference<Thread> worker = new AtomicReference<>();
        private final CountDownLatch entered = new CountDownLatch(1);

        /* default */ void record() {
            worker.set(Thread.currentThread());
            entered.countDown();
        }

        /* default */ void awaitExit() throws InterruptedException {
            assertThat(entered.await(AWAIT_SECONDS, TimeUnit.SECONDS)).as("the step ran").isTrue();
            assertThat(worker.get().join(Duration.ofSeconds(AWAIT_SECONDS)))
                    .as("the worker that ran the step has ended").isTrue();
        }
    }

    /** The version-enforcing store, with every accepted save also announced on a queue and a log. */
    private static final class SignallingStore implements FlowSnapshotStore {

        /* default */ final VersionEnforcingSnapshotStore versions = new VersionEnforcingSnapshotStore();
        /* default */ final List<FlowSnapshot> log = new CopyOnWriteArrayList<>();
        private final LinkedBlockingQueue<FlowSnapshot> saved = new LinkedBlockingQueue<>();

        private final AtomicReference<CountDownLatch> completedEntered = new AtomicReference<>();
        private final AtomicReference<CountDownLatch> completedGate = new AtomicReference<>();
        private final AtomicBoolean refuseCompleted = new AtomicBoolean();

        /* default */ CountDownLatch holdCompletedSave() {
            CountDownLatch entered = new CountDownLatch(1);
            completedEntered.set(entered);
            completedGate.set(new CountDownLatch(1));
            return entered;
        }

        /* default */ void releaseCompletedSave() {
            completedGate.get().countDown();
        }

        /* default */ void refuseCompletedSaves() {
            refuseCompleted.set(true);
        }

        @Override
        public void save(FlowSnapshot snapshot) {
            if (snapshot.state() == FlowState.COMPLETED) {
                if (refuseCompleted.get()) {
                    throw new IllegalStateException("the completed row is refused");
                }
                CountDownLatch gate = completedGate.get();
                if (gate != null) {
                    completedEntered.get().countDown();
                    awaitGate(gate);
                }
            }
            versions.save(snapshot);
            log.add(snapshot);
            saved.add(snapshot);
        }

        @Override
        public Optional<FlowSnapshot> load(long instanceIdMost, long instanceIdLeast) {
            return versions.load(instanceIdMost, instanceIdLeast);
        }

        @Override
        public void delete(long instanceIdMost, long instanceIdLeast) {
            versions.delete(instanceIdMost, instanceIdLeast);
        }

        @Override
        public boolean exists(long instanceIdMost, long instanceIdLeast) {
            return versions.exists(instanceIdMost, instanceIdLeast);
        }

        private static void awaitGate(CountDownLatch gate) {
            try {
                assertThat(gate.await(AWAIT_SECONDS, TimeUnit.SECONDS)).as("gate released").isTrue();
            } catch (InterruptedException _) {
                Thread.currentThread().interrupt();
            }
        }

        /* default */ void awaitSave(FlowKey key, FlowState state) throws InterruptedException {
            FlowSnapshot next = saved.poll(AWAIT_SECONDS, TimeUnit.SECONDS);
            while (next != null && !matches(next, key, state)) {
                next = saved.poll(AWAIT_SECONDS, TimeUnit.SECONDS);
            }
            assertThat(next).as("a " + state + " save for the instance").isNotNull();
        }

        private static boolean matches(FlowSnapshot s, FlowKey key, FlowState state) {
            return s.instanceIdMost() == key.instanceIdMost()
                    && s.instanceIdLeast() == key.instanceIdLeast()
                    && s.state() == state;
        }
    }
}
