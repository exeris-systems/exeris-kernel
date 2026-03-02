/*
 * Copyright (C) 2025-2026 Exeris. All rights reserved.
 *
 * This code is part of the Exeris Systems.
 * Distributed under the proprietary Exeris Software License.
 * Unauthorized copying or distribution is prohibited.
 */
package eu.exeris.kernel.tck.contract.flow;

import eu.exeris.kernel.spi.flow.FlowEngine;
import eu.exeris.kernel.spi.flow.model.FlowContext;
import eu.exeris.kernel.spi.flow.model.FlowDefinition;
import eu.exeris.kernel.spi.flow.model.FlowExecutionPlan;
import eu.exeris.kernel.spi.flow.model.FlowOutcome;
import eu.exeris.kernel.spi.flow.model.FlowSnapshot;
import eu.exeris.kernel.spi.flow.model.FlowSnapshotStore;
import eu.exeris.kernel.spi.flow.model.FlowState;
import eu.exeris.kernel.spi.flow.model.FlowStepAction;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * TCK: Abstract base for Flow / Saga recovery verification.
 *
 * <h2>Contract (#25)</h2>
 * <p>Verifies that the {@link FlowEngine} correctly resumes or compensates a saga
 * after a catastrophic failure, and that replayed flows never trigger duplicate
 * business actions.
 *
 * <h2>SPI used — nothing invented</h2>
 * <ul>
 *   <li>{@link FlowEngine} — start / close / plans() / scheduler()</li>
 *   <li>{@link FlowSnapshotStore} — save / load / exists (checkpoint persistence)</li>
 *   <li>{@link FlowContext} — instanceIdMost / instanceIdLeast / currentStep / state</li>
 *   <li>{@link FlowState} — RUNNING / PARKED / COMPENSATING / FAILED_ROLLEDBACK</li>
 *   <li>{@link FlowOutcome} — CONTINUE / PARK / FAIL</li>
 * </ul>
 *
 * <h2>Three scenarios</h2>
 * <ol>
 *   <li><b>Mid-Saga Kill</b> — engine is force-closed while a flow is PARKED; after rebuild
 *       the snapshot must exist and the flow must resume from the checkpoint step.</li>
 *   <li><b>Idempotency</b> — re-scheduling a COMPLETED flow context must not re-execute
 *       the business step.</li>
 *   <li><b>Compensation</b> — a step returning {@link FlowOutcome#FAIL} must drive the
 *       engine to execute compensation actions in reverse step order.</li>
 * </ol>
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * class CommunityFlowSagaRecoveryTest extends AbstractSagaRecoveryTck {
 *     \@Override protected FlowEngine  createEngine()  { return provider.createEngine(cfg); }
 *     \@Override protected FlowEngine  rebuildEngine() { return provider.createEngine(cfg); }
 *     \@Override protected FlowSnapshotStore snapshotStore() { return sharedStore; }
 * }
 * }</pre>
 *
 * @since 0.5.0
 * @see AbstractFlowEngineTck
 * @see AbstractFlowSchedulerTck
 */
@DisplayName("Saga Recovery TCK")
public abstract class AbstractSagaRecoveryTck {

    // =========================================================================
    // Template methods
    // =========================================================================

    /** Creates an unstarted fresh {@link FlowEngine} (first boot). The TCK calls {@code start()}. */
    protected abstract FlowEngine createEngine();

    /**
     * Creates an unstarted second {@link FlowEngine} backed by the <em>same</em>
     * {@link FlowSnapshotStore} — simulates JVM restart without snapshot loss.
     * The TCK calls {@code start()} after this method returns.
     */
    protected abstract FlowEngine rebuildEngine();

    /**
     * Returns the {@link FlowSnapshotStore} shared between both engine instances.
     * Must be the same store used by both {@link #createEngine()} and {@link #rebuildEngine()}.
     */
    protected abstract FlowSnapshotStore snapshotStore();

    // =========================================================================
    // Lifecycle
    // =========================================================================

    private FlowEngine engine;

    @BeforeEach
    final void setUp() {
        engine = createEngine();
        engine.start();
    }

    @AfterEach
    final void tearDown() {
        if (engine != null) engine.close();
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private boolean snapshotExists(FlowContext ctx) {
        return snapshotStore().exists(ctx.instanceIdMost(), ctx.instanceIdLeast());
    }

    private Optional<FlowSnapshot> loadSnapshot(FlowContext ctx) {
        return snapshotStore().load(ctx.instanceIdMost(), ctx.instanceIdLeast());
    }

    private boolean awaitCondition(java.util.function.BooleanSupplier condition, int timeoutSeconds) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds);
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) return true;
            java.util.concurrent.locks.LockSupport.parkNanos(1_000_000L);
        }
        return false;
    }

    // =========================================================================
    // Test 1 — Mid-Saga Kill
    // =========================================================================

    @Nested
    @DisplayName("Mid-Saga Kill — engine restarts from FlowSnapshotStore checkpoint")
    class MidSagaKillTest {

        @Test
        @Timeout(value = 30, unit = TimeUnit.SECONDS)
        @DisplayName("PARKED flow snapshot survives engine force-close; resumes from checkpoint step")
        void sagaResumesFromCheckpointAfterRebuild() {
            AtomicInteger step0Exec = new AtomicInteger();
            AtomicInteger step2Exec = new AtomicInteger();

            FlowStepAction step0 = _ -> { step0Exec.incrementAndGet(); return FlowOutcome.CONTINUE; };
            FlowStepAction step1 = _ -> FlowOutcome.PARK;
            FlowStepAction step2 = _ -> { step2Exec.incrementAndGet(); return FlowOutcome.CONTINUE; };

            FlowDefinition def = engine.plans().newDefinition("kill-resume-saga")
                    .step("validate", step0, null)
                    .step("pay",      step1, null)
                    .step("ship",     step2, null)
                    .transition(0, 1)
                    .transition(1, 2)
                    .build();

            FlowExecutionPlan plan = engine.plans().compile(def);
            FlowContext ctx = TestFlowContexts.create(UUID.randomUUID().toString(), "kill-resume-saga");

            engine.scheduler().schedule(plan, ctx);

            // Wait for PARK — FlowSnapshotStore.save() must be called on PARK transition
            assertThat(awaitCondition(() -> snapshotExists(ctx), 5))
                    .as("FlowSnapshotStore MUST contain a checkpoint after PARK transition")
                    .isTrue();

            // Force-close — simulate JVM kill (no graceful drain)
            engine.close();
            engine = null;

            // Snapshot must still be in the store (durable)
            assertThat(snapshotExists(ctx))
                    .as("Checkpoint MUST remain in FlowSnapshotStore after engine force-close")
                    .isTrue();

            Optional<FlowSnapshot> snap = loadSnapshot(ctx);
            assertThat(snap).as("Checkpoint must be loadable after engine rebuild").isPresent();
            assertThat(snap.get().currentStep()).as("Checkpoint must be at step index 1 (pay)").isEqualTo(1);
            assertThat(snap.get().state()).as("Checkpoint state must be PARKED").isEqualTo(FlowState.PARKED);

            // Rebuild and wake
            engine = rebuildEngine();
            engine.start();
            engine.scheduler().wake(ctx);

            // step2 must execute; step0 must NOT re-execute (already completed before kill)
            assertThat(awaitCondition(() -> step2Exec.get() >= 1, 5))
                    .as("step2 (ship) MUST execute after wake from checkpoint").isTrue();

            assertThat(step0Exec.get())
                    .as("step0 (validate) MUST NOT re-execute after resume — idempotency guard")
                    .isEqualTo(1);
        }
    }

    // =========================================================================
    // Test 2 — Saga Idempotency
    // =========================================================================

    @Nested
    @DisplayName("Saga Idempotency — re-scheduling a completed flow has no business effect")
    class SagaIdempotencyTest {

        @Test
        @Timeout(value = 30, unit = TimeUnit.SECONDS)
        @DisplayName("Re-scheduling a COMPLETED context does not re-execute the business step")
        void completedFlowNotReExecutedOnReschedule() throws InterruptedException {
            AtomicInteger executions = new AtomicInteger();
            CountDownLatch completed = new CountDownLatch(1);

            FlowStepAction action = _ -> {
                executions.incrementAndGet();
                completed.countDown();
                return FlowOutcome.CONTINUE;
            };

            FlowDefinition def = engine.plans().newDefinition("idempotent-flow")
                    .step("charge", action, null)
                    .build();

            FlowExecutionPlan plan = engine.plans().compile(def);
            FlowContext ctx = TestFlowContexts.create(UUID.randomUUID().toString(), "idempotent-flow");

            engine.scheduler().schedule(plan, ctx);
            assertThat(completed.await(5, TimeUnit.SECONDS)).as("Flow must complete on first schedule").isTrue();
            assertThat(executions.get()).isEqualTo(1);

            // Re-schedule the same context — simulates duplicate event replay
            engine.scheduler().schedule(plan, ctx);
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(300));

            assertThat(executions.get())
                    .as(
                        "IDEMPOTENCY VIOLATION: step executed %d times after re-scheduling a COMPLETED flow.\n" +
                        "FlowEngine MUST guard duplicate execution via FlowSnapshotStore.exists().",
                        executions.get()
                    )
                    .isEqualTo(1);

            // give any spurious re-execution time to manifest
            assertThat(awaitCondition(() -> false, 1)).isFalse(); // non-blocking 1s settle
        }

        @Test
        @Timeout(value = 30, unit = TimeUnit.SECONDS)
        @DisplayName("10 concurrent re-schedules of the same instance execute the step exactly once")
        void concurrentReschedulesExecuteStepExactlyOnce() throws InterruptedException {
            AtomicInteger executions = new AtomicInteger();

            FlowStepAction action = _ -> {
                executions.incrementAndGet();
                return FlowOutcome.CONTINUE;
            };

            FlowDefinition def = engine.plans().newDefinition("concurrent-idem-flow")
                    .step("reserve", action, null)
                    .build();

            FlowExecutionPlan plan = engine.plans().compile(def);
            FlowContext ctx = TestFlowContexts.create(UUID.randomUUID().toString(), "concurrent-idem-flow");

            int concurrency     = 10;
            CountDownLatch go   = new CountDownLatch(1);
            CountDownLatch done = new CountDownLatch(concurrency);

            for (int i = 0; i < concurrency; i++) {
                Thread.ofVirtual().name("exeris-idem-", i).start(() -> {
                    try {
                        go.await();
                        engine.scheduler().schedule(plan, ctx);
                    } catch (Exception _) {
                        // scheduling may reject duplicate — that is acceptable
                    } finally {
                        done.countDown();
                    }
                });
            }

            go.countDown();
            assertThat(done.await(10, TimeUnit.SECONDS)).isTrue();
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(300));

            assertThat(executions.get())
                    .as(
                        "IDEMPOTENCY VIOLATION: %d concurrent schedules caused the step to execute " +
                        "%d times. Expected exactly 1.",
                        concurrency, executions.get()
                    )
                    .isEqualTo(1);

            // settle
            assertThat(awaitCondition(() -> false, 1)).isFalse();
        }
    }

    // =========================================================================
    // Test 3 — Saga Compensation
    // =========================================================================

    @Nested
    @DisplayName("Saga Compensation — FAIL drives engine through COMPENSATING to FAILED_ROLLEDBACK")
    class SagaCompensationTest {

        @Test
        @Timeout(value = 30, unit = TimeUnit.SECONDS)
        @DisplayName("Step returning FAIL triggers compensation in reverse step order")
        void failStepTriggersReverseCompensation() throws InterruptedException {
            AtomicInteger comp0 = new AtomicInteger();
            AtomicInteger comp1 = new AtomicInteger();
            AtomicReference<String> order = new AtomicReference<>("");
            CountDownLatch compensationDone = new CountDownLatch(2);

            FlowStepAction compensate0 = _ -> {
                comp0.incrementAndGet();
                order.updateAndGet(s -> s + "step0|");
                compensationDone.countDown();
                return FlowOutcome.CONTINUE;
            };
            FlowStepAction compensate1 = _ -> {
                comp1.incrementAndGet();
                order.updateAndGet(s -> s + "step1|");
                compensationDone.countDown();
                return FlowOutcome.CONTINUE;
            };

            FlowDefinition def = engine.plans().newDefinition("compensation-flow")
                    .step("book-hotel",  _ -> FlowOutcome.CONTINUE, compensate0)
                    .step("book-flight", _ -> FlowOutcome.CONTINUE, compensate1)
                    .step("charge-card", _ -> FlowOutcome.FAIL,     null)
                    .transition(0, 1)
                    .transition(1, 2)
                    .build();

            FlowExecutionPlan plan = engine.plans().compile(def);
            FlowContext ctx = TestFlowContexts.create(UUID.randomUUID().toString(), "compensation-flow");

            engine.scheduler().schedule(plan, ctx);

            assertThat(compensationDone.await(10, TimeUnit.SECONDS))
                    .as(
                        "COMPENSATION FAILURE: FlowEngine did not execute both compensation actions " +
                        "within 10 s after a FAIL outcome.\n" +
                        "FlowEngine MUST drive the flow to COMPENSATING and call compensation " +
                        "actions in reverse step order."
                    )
                    .isTrue();

            assertThat(comp1.get()).as("Compensation for step1 (book-flight) must fire").isEqualTo(1);
            assertThat(comp0.get()).as("Compensation for step0 (book-hotel) must fire").isEqualTo(1);

            // Reverse order: step1 compensation must appear before step0 compensation
            String observedOrder = order.get();
            assertThat(observedOrder.indexOf("step1|"))
                    .as(
                        "COMPENSATION ORDER VIOLATION: compensation must be reverse of execution order " +
                        "(step1 before step0). Observed: %s", observedOrder
                    )
                    .isLessThan(observedOrder.indexOf("step0|"));
        }

        @Test
        @Timeout(value = 30, unit = TimeUnit.SECONDS)
        @DisplayName("FlowSnapshot transitions to COMPENSATING / FAILED_ROLLEDBACK after FAIL")
        void snapshotStateTransitionsAfterFail() throws InterruptedException {
            CountDownLatch failFired = new CountDownLatch(1);

            FlowDefinition def = engine.plans().newDefinition("state-transition-flow")
                    .step("action", _ -> { failFired.countDown(); return FlowOutcome.FAIL; }, null)
                    .build();

            FlowExecutionPlan plan = engine.plans().compile(def);
            FlowContext ctx = TestFlowContexts.create(UUID.randomUUID().toString(), "state-transition-flow");

            engine.scheduler().schedule(plan, ctx);
            assertThat(failFired.await(5, TimeUnit.SECONDS)).isTrue();
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(200));

            java.util.Optional<FlowSnapshot> snapshotOpt = loadSnapshot(ctx);
            org.assertj.core.api.Assertions.assertThat(snapshotOpt)
                    .as("After FAIL, FlowSnapshot MUST be persisted for context %s", ctx)
                    .isPresent();
            org.assertj.core.api.Assertions.assertThat(snapshotOpt.get().state())
                    .as("After FAIL, FlowSnapshot state MUST be COMPENSATING or FAILED_ROLLEDBACK, was: %s", snapshotOpt.get().state())
                    .isIn(FlowState.COMPENSATING, FlowState.FAILED_ROLLEDBACK);

            assertThat(awaitCondition(() -> false, 1)).isFalse(); // settle
        }
    }
}

