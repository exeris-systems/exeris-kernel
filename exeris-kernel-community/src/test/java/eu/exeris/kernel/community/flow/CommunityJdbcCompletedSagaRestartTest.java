/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.flow;

import eu.exeris.kernel.community.persistence.CommunityPersistenceProvider;
import eu.exeris.kernel.spi.exceptions.flow.FlowEngineException;
import eu.exeris.kernel.spi.flow.FlowEngine;
import eu.exeris.kernel.spi.flow.FlowEngineConfig;
import eu.exeris.kernel.spi.flow.model.FlowContext;
import eu.exeris.kernel.spi.flow.model.FlowDefinition;
import eu.exeris.kernel.spi.flow.model.FlowExecutionPlan;
import eu.exeris.kernel.spi.flow.model.FlowOutcome;
import eu.exeris.kernel.spi.flow.model.FlowSnapshot;
import eu.exeris.kernel.spi.flow.model.FlowState;
import eu.exeris.kernel.spi.persistence.PersistenceConfig;
import eu.exeris.kernel.spi.persistence.PersistenceEngine;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A completed saga is not run again by an engine rebuilt over the same JDBC snapshot store.
 *
 * <p>Runs the engine with the configuration the Community flow subsystem builds (persistence on,
 * unbounded terminal catalog), so the only thing that can tell the second engine the instance
 * finished is the terminal row the first one left in the database.
 */
@DisplayName("Community: a completed saga is not run again after a restart over JdbcFlowSnapshotStore")
class CommunityJdbcCompletedSagaRestartTest {

    private static final long JOIN_SECONDS = 5L;
    private static final long NEGATIVE_WAIT_MILLIS = 300L;

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    @DisplayName("a flow that never parked is not run again, and a fresh instance still runs")
    void neverParkedCompletionIsAbsorbedAfterRestart() throws InterruptedException {
        AtomicInteger executions = new AtomicInteger();
        CountDownLatch rerun = new CountDownLatch(1);
        AtomicReference<Probe> probe = new AtomicReference<>(new Probe());
        FlowContext ctx = context("jdbc-never-parked-instance", "jdbc-never-parked");
        FlowContext fresh = context("jdbc-never-parked-fresh-instance", "jdbc-never-parked");

        try (PersistenceEngine pe = newH2Engine()) {
            JdbcFlowSnapshotStore storeA = new JdbcFlowSnapshotStore(pe, "kernel-A");
            JdbcFlowSnapshotStore storeB = new JdbcFlowSnapshotStore(pe, "kernel-B");

            FlowDefinition def;
            try (FlowEngine a = startedEngine(storeA)) {
                def = a.plans().newDefinition("jdbc-never-parked")
                        .step("charge", _ -> {
                            if (executions.incrementAndGet() > 1) {
                                rerun.countDown();
                            }
                            probe.get().record();
                            return FlowOutcome.CONTINUE;
                        }, null)
                        .build();
                a.scheduler().schedule(a.plans().compile(def), ctx);
                probe.get().awaitWorkerExit();
                assertThat(executions.get()).isEqualTo(1);
            }
            assertCompleted(storeB.load(ctx.instanceIdMost(), ctx.instanceIdLeast()));

            try (FlowEngine b = startedEngine(storeB)) {
                FlowExecutionPlan plan = b.plans().compile(def);

                b.scheduler().schedule(plan, ctx);

                assertThat(rerun.await(NEGATIVE_WAIT_MILLIS, TimeUnit.MILLISECONDS))
                        .as("IDEMPOTENCY VIOLATION: a COMPLETED saga ran again after a restart")
                        .isFalse();
                assertThat(executions.get()).isEqualTo(1);
                assertThat(b.stats().completedFlows()).isZero();
                assertCompleted(storeB.load(ctx.instanceIdMost(), ctx.instanceIdLeast()));

                // Control: a new instance of the same plan still runs.
                probe.set(new Probe());
                b.scheduler().schedule(plan, fresh);
                probe.get().awaitWorkerExit();
                assertThat(b.stats().completedFlows()).isEqualTo(1);
            }
        }
    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    @DisplayName("a flow that parked and completed is not offered as parked, nor run again")
    void parkedThenCompletedIsNotResurrectedAfterRestart() throws InterruptedException {
        AtomicInteger executions = new AtomicInteger();
        CountDownLatch rerun = new CountDownLatch(1);
        Probe waitProbe = new Probe();
        Probe finishProbe = new Probe();
        FlowContext ctx = context("jdbc-parked-completed-instance", "jdbc-parked-completed");

        try (PersistenceEngine pe = newH2Engine()) {
            JdbcFlowSnapshotStore storeA = new JdbcFlowSnapshotStore(pe, "kernel-A");
            JdbcFlowSnapshotStore storeB = new JdbcFlowSnapshotStore(pe, "kernel-B");

            FlowDefinition def;
            try (FlowEngine a = startedEngine(storeA)) {
                def = a.plans().newDefinition("jdbc-parked-completed")
                        .step("await-callback", _ -> {
                            waitProbe.record();
                            return FlowOutcome.PARK;
                        }, null)
                        .step("ship", _ -> {
                            if (executions.incrementAndGet() > 1) {
                                rerun.countDown();
                            }
                            finishProbe.record();
                            return FlowOutcome.CONTINUE;
                        }, null)
                        .transition(0, 1)
                        .build();
                a.scheduler().schedule(a.plans().compile(def), ctx);
                waitProbe.awaitWorkerExit();
                a.scheduler().wake(ctx.instanceIdMost(), ctx.instanceIdLeast());
                finishProbe.awaitWorkerExit();
                assertThat(executions.get()).isEqualTo(1);
            }
            assertCompleted(storeB.load(ctx.instanceIdMost(), ctx.instanceIdLeast()));

            try (FlowEngine b = startedEngine(storeB)) {
                FlowExecutionPlan plan = b.plans().compile(def);

                assertThat(b.scheduler().lookupParked(ctx.instanceIdMost(), ctx.instanceIdLeast()))
                        .as("a finished instance MUST NOT be offered as parked").isEmpty();
                assertThatThrownBy(() -> b.scheduler().wake(ctx.instanceIdMost(), ctx.instanceIdLeast()))
                        .matches(FlowEngineException::isNotParked);
                assertThat(storeB.listParked())
                        .as("the terminal row is not enumerated as parked")
                        .noneMatch(row -> row.instanceIdMost() == ctx.instanceIdMost()
                                && row.instanceIdLeast() == ctx.instanceIdLeast());
                b.scheduler().schedule(plan, ctx);

                assertThat(rerun.await(NEGATIVE_WAIT_MILLIS, TimeUnit.MILLISECONDS))
                        .as("IDEMPOTENCY VIOLATION: the steps after the park ran again")
                        .isFalse();
                assertThat(executions.get()).isEqualTo(1);
                assertCompleted(storeB.load(ctx.instanceIdMost(), ctx.instanceIdLeast()));
            }
        }
    }

    private static void assertCompleted(java.util.Optional<FlowSnapshot> row) {
        assertThat(row)
                .as("a COMPLETED row is the proof that outlives the restart")
                .hasValueSatisfying(r -> assertThat(r.state()).isEqualTo(FlowState.COMPLETED));
    }

    private static FlowEngine startedEngine(JdbcFlowSnapshotStore store) {
        FlowEngineConfig d = FlowEngineConfig.defaults("Community/JdbcCompletedRestart");
        FlowEngineConfig config = new FlowEngineConfig(
                d.engineName(), d.maxConcurrentFlows(), d.timeoutDurationNanos(), d.maxSteps(),
                d.maxTransitions(), d.maxExecutionPlans(), d.schedulerQueueCapacity(), d.partitionName(),
                d.partitionBytes(), true, d.compensationEnabled());
        FlowEngine engine = new ScopedStoreFlowEngine(new CommunityFlowProvider().createEngine(config), store);
        engine.start();
        return engine;
    }

    private static PersistenceEngine newH2Engine() {
        PersistenceConfig cfg = new PersistenceConfig(
                "jdbc:h2:mem:completed_restart_" + System.nanoTime() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
                "sa",
                "",
                4,
                1,
                5_000L,
                60_000L,
                600_000L,
                false,
                false,
                false,
                0,
                Map.of("run.migrations", "true"));
        return new CommunityPersistenceProvider().createEngine(cfg);
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
                return Long.MAX_VALUE;
            }
        };
    }

    /**
     * Records the thread that ran a step. That thread ends only after the run's bookkeeping,
     * including the durable write a transition makes, so joining it orders the assertions after the
     * write without polling.
     */
    private static final class Probe {

        private final AtomicReference<Thread> worker = new AtomicReference<>();
        private final CountDownLatch entered = new CountDownLatch(1);

        /* default */ void record() {
            worker.set(Thread.currentThread());
            entered.countDown();
        }

        /* default */ void awaitWorkerExit() throws InterruptedException {
            assertThat(entered.await(JOIN_SECONDS, TimeUnit.SECONDS)).as("the step ran").isTrue();
            assertThat(worker.get().join(Duration.ofSeconds(JOIN_SECONDS)))
                    .as("the worker that ran the step has ended").isTrue();
        }
    }
}
