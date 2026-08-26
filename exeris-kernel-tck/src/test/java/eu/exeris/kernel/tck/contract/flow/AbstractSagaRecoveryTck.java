/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.tck.contract.flow;

import eu.exeris.kernel.spi.exceptions.KernelErrorCodes;
import eu.exeris.kernel.spi.exceptions.flow.FlowEngineException;
import eu.exeris.kernel.spi.flow.FlowEngine;
import eu.exeris.kernel.spi.flow.model.FlowContext;
import eu.exeris.kernel.spi.flow.model.FlowDefinition;
import eu.exeris.kernel.spi.flow.model.FlowExecutionPlan;
import eu.exeris.kernel.spi.flow.model.FlowOutcome;
import eu.exeris.kernel.spi.flow.model.FlowSnapshot;
import eu.exeris.kernel.spi.flow.model.FlowSnapshotStore;
import eu.exeris.kernel.spi.flow.model.FlowState;
import eu.exeris.kernel.spi.flow.model.FlowStepAction;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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

    /**
     * Number of concurrent flow instances driven through the restart-under-load
     * scenario ({@link RestartUnderLoad}). Mirrors
     * {@code AbstractFlowSchedulerTck.avalancheScheduleCount()} so bindings can tune N.
     *
     * <p>The default ({@value} ) MUST stay deterministic on a 2-vCPU runner — larger N
     * is a benchmark, not a contract gate. Enterprise ring-buffer-bounded bindings may
     * override down, never up for the always-on unit lane.
     */
    protected int restartLoadCount() { return 16; }

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

    /** Whether every instance's durable checkpoint has been reclaimed. */
    private boolean allCheckpointsReclaimed(List<FlowContext> contexts) {
        for (FlowContext ctx : contexts) {
            if (loadSnapshot(ctx).isPresent()) {
                return false;
            }
        }
        return true;
    }

    private boolean awaitCondition(BooleanSupplier condition, int timeoutSeconds) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds);
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) return true;
            LockSupport.parkNanos(1_000_000L);
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

            // Rebuild and wake — re-compile definition on rebuilt engine (simulates app restart re-registration)
            engine = rebuildEngine();
            engine.start();
            engine.plans().compile(def);
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
    // Definition changed under a parked saga — fail-closed SCHEMA_MISMATCH
    // =========================================================================

    @Nested
    @DisplayName("Definition changed under a parked saga — fail-closed SCHEMA_MISMATCH")
    class SchemaMismatchOnResume {

        @Test
        @Timeout(value = 30, unit = TimeUnit.SECONDS)
        @DisplayName("a parked resume step the redeployed definition removed fails closed (EX-FLOW-7002 / SCHEMA_MISMATCH), never silent stale-index replay")
        void redeployedDefinitionMissingParkedStepFailsClosed() {
            AtomicInteger anyStepReexec = new AtomicInteger();

            FlowStepAction validate = _ -> { anyStepReexec.incrementAndGet(); return FlowOutcome.CONTINUE; };
            FlowStepAction pay      = _ -> { anyStepReexec.incrementAndGet(); return FlowOutcome.CONTINUE; };
            FlowStepAction ship     = _ -> FlowOutcome.PARK;

            // v1: 3 steps; parks on the last step (index 2, "ship").
            FlowDefinition v1 = engine.plans().newDefinition("schema-mismatch-saga")
                    .step("validate", validate, null)
                    .step("pay",      pay,      null)
                    .step("ship",     ship,     null)
                    .transition(0, 1)
                    .transition(1, 2)
                    .build();

            FlowExecutionPlan plan = engine.plans().compile(v1);
            FlowContext ctx = TestFlowContexts.create(UUID.randomUUID().toString(), "schema-mismatch-saga");
            engine.scheduler().schedule(plan, ctx);

            assertThat(awaitCondition(() -> snapshotExists(ctx), 5))
                    .as("saga MUST park (checkpoint persisted) before the redeploy").isTrue();
            Optional<FlowSnapshot> snap = loadSnapshot(ctx);
            assertThat(snap).isPresent();
            assertThat(snap.get().currentStep())
                    .as("parked at the last step index (2, ship)").isEqualTo(2);
            int reexecBaseline = anyStepReexec.get();

            // Force-close, rebuild, and re-register a SHORTER definition — "ship" removed (stepCount 2).
            engine.close();
            engine = rebuildEngine();
            engine.start();
            FlowDefinition v2 = engine.plans().newDefinition("schema-mismatch-saga")
                    .step("validate", validate, null)
                    .step("pay",      pay,      null)
                    .transition(0, 1)
                    .build();
            engine.plans().compile(v2);

            // Resume MUST fail closed — persisted step 2 no longer exists in v2 (stepCount 2) — and MUST
            // be synchronous (before any VT launch / step replay), so no stale-index step ever runs.
            assertThatThrownBy(() -> engine.scheduler().wake(ctx))
                    .as("version-blind resume MUST fail closed, never replay the stale step index")
                    .isInstanceOf(FlowEngineException.class)
                    .satisfies(thrown -> {
                        FlowEngineException ex = (FlowEngineException) thrown;
                        assertThat(ex.errorCode())
                                .as("carries EX-FLOW-7002").isEqualTo(KernelErrorCodes.EX_FLOW_7002);
                        assertThat(ex.rawArgs()[1])
                                .as("phase=SCHEMA_MISMATCH").isEqualTo("SCHEMA_MISMATCH");
                        assertThat(ex.rawArgs()[2])
                                .as("reason=STEP_OUT_OF_RANGE (stable tooling key)").isEqualTo("STEP_OUT_OF_RANGE");
                        assertThat(ex.rawArgs()[3])
                                .as("contextValue = the out-of-range persisted step").isEqualTo(2);
                    });

            assertThat(anyStepReexec.get())
                    .as("fail-closed: NO step re-executes on the rejected resume")
                    .isEqualTo(reexecBaseline);
        }

        @Test
        @Timeout(value = 30, unit = TimeUnit.SECONDS)
        @DisplayName("a same-arity reorder fails closed (reason=STEP_IDENTITY_MISMATCH) — the case the bounds guard cannot see")
        void redeployedDefinitionReorderingStepsFailsClosed() {
            AtomicInteger anyStepReexec = new AtomicInteger();

            FlowStepAction validate = _ -> { anyStepReexec.incrementAndGet(); return FlowOutcome.CONTINUE; };
            FlowStepAction pay      = _ -> { anyStepReexec.incrementAndGet(); return FlowOutcome.CONTINUE; };
            FlowStepAction ship     = _ -> FlowOutcome.PARK;

            FlowDefinition v1 = engine.plans().newDefinition("reorder-mismatch-saga")
                    .step("validate", validate, null)
                    .step("pay",      pay,      null)
                    .step("ship",     ship,     null)
                    .transition(0, 1)
                    .transition(1, 2)
                    .build();

            FlowExecutionPlan plan = engine.plans().compile(v1);
            FlowContext ctx = TestFlowContexts.create(UUID.randomUUID().toString(), "reorder-mismatch-saga");
            engine.scheduler().schedule(plan, ctx);

            assertThat(awaitCondition(() -> snapshotExists(ctx), 5))
                    .as("saga MUST park before the redeploy").isTrue();
            Optional<FlowSnapshot> snap = loadSnapshot(ctx);
            assertThat(snap).isPresent();
            assertThat(snap.get().currentStepName())
                    .as("the snapshot must record WHICH step it parked at, not only where — without "
                            + "this the reorder below is undetectable (ADR-062)")
                    .contains("ship");
            int reexecBaseline = anyStepReexec.get();

            // Redeploy with the SAME step count and a different order. Index 2 stays in range, so the
            // bounds guard passes it; only the identity check can tell that index 2 is now "pay".
            engine.close();
            engine = rebuildEngine();
            engine.start();
            FlowDefinition v2 = engine.plans().newDefinition("reorder-mismatch-saga")
                    .step("validate", validate, null)
                    .step("ship",     ship,     null)
                    .step("pay",      pay,      null)
                    .transition(0, 1)
                    .transition(1, 2)
                    .build();
            engine.plans().compile(v2);

            assertThatThrownBy(() -> engine.scheduler().wake(ctx))
                    .as("a same-arity reorder MUST fail closed — the index is still valid, which is "
                            + "exactly why replaying it would bind the saga to the wrong step")
                    .isInstanceOf(FlowEngineException.class)
                    .satisfies(thrown -> {
                        FlowEngineException ex = (FlowEngineException) thrown;
                        assertThat(ex.errorCode()).isEqualTo(KernelErrorCodes.EX_FLOW_7002);
                        assertThat(ex.rawArgs()[1]).isEqualTo("SCHEMA_MISMATCH");
                        assertThat(ex.rawArgs()[2])
                                .as("a distinct reason from STEP_OUT_OF_RANGE: the step did not vanish, "
                                        + "the step at that position became something else")
                                .isEqualTo("STEP_IDENTITY_MISMATCH");
                        assertThat(ex.rawArgs()[3])
                                .as("contextValue = the persisted step index").isEqualTo(2);
                    });

            assertThat(anyStepReexec.get())
                    .as("fail-closed: NO step re-executes on the rejected resume")
                    .isEqualTo(reexecBaseline);
        }

        @Test
        @Timeout(value = 30, unit = TimeUnit.SECONDS)
        @DisplayName("a snapshot with no recorded identity fails closed (reason=STEP_IDENTITY_ABSENT) — a pre-0.11 row is not resumed by position")
        void snapshotWithoutRecordedIdentityFailsClosed() {
            AtomicInteger anyStepReexec = new AtomicInteger();

            FlowStepAction validate = _ -> { anyStepReexec.incrementAndGet(); return FlowOutcome.CONTINUE; };
            FlowStepAction pay      = _ -> { anyStepReexec.incrementAndGet(); return FlowOutcome.CONTINUE; };
            FlowStepAction ship     = _ -> FlowOutcome.PARK;

            FlowDefinition def = engine.plans().newDefinition("identity-absent-saga")
                    .step("validate", validate, null)
                    .step("pay",      pay,      null)
                    .step("ship",     ship,     null)
                    .transition(0, 1)
                    .transition(1, 2)
                    .build();

            FlowExecutionPlan plan = engine.plans().compile(def);
            FlowContext ctx = TestFlowContexts.create(UUID.randomUUID().toString(), "identity-absent-saga");
            engine.scheduler().schedule(plan, ctx);

            assertThat(awaitCondition(() -> snapshotExists(ctx), 5)).as("saga MUST park").isTrue();
            FlowSnapshot parked = loadSnapshot(ctx).orElseThrow();
            int reexecBaseline = anyStepReexec.get();

            // Rewrite the row exactly as a pre-0.11 kernel would have left it: same state, same index,
            // no identity. The definition is NOT changed — so nothing but the missing identity can
            // cause the rejection, and a guard that only compared names would happily admit this.
            FlowSnapshot legacy = new FlowSnapshot(
                    parked.instanceIdMost(), parked.instanceIdLeast(), parked.definitionName(),FlowDefinition.INITIAL_VERSION,
                    parked.currentStep(), Optional.empty(), parked.state(), parked.lastUpdate(),
                    parked.timeout(), parked.compensationStack(), new String[0], parked.stackPointer(),
                    parked.opaqueState(), parked.schemaVersion());
            snapshotStore().save(legacy);

            engine.close();
            engine = rebuildEngine();
            engine.start();
            engine.plans().compile(def);

            assertThatThrownBy(() -> engine.scheduler().wake(ctx))
                    .as("resuming it would mean trusting the index again — the behaviour ADR-062 "
                            + "removes — so an unvalidatable snapshot is refused, not assumed safe")
                    .isInstanceOf(FlowEngineException.class)
                    .satisfies(thrown -> {
                        FlowEngineException ex = (FlowEngineException) thrown;
                        assertThat(ex.errorCode()).isEqualTo(KernelErrorCodes.EX_FLOW_7002);
                        assertThat(ex.rawArgs()[1]).isEqualTo("SCHEMA_MISMATCH");
                        assertThat(ex.rawArgs()[2])
                                .as("distinct from STEP_IDENTITY_MISMATCH: nothing disagreed, there "
                                        + "was simply nothing to compare — and the operator response "
                                        + "differs (drain before upgrading, not fix the definition)")
                                .isEqualTo(FlowEngineException.REASON_STEP_IDENTITY_ABSENT);
                    });

            assertThat(anyStepReexec.get())
                    .as("fail-closed: NO step re-executes on the rejected resume")
                    .isEqualTo(reexecBaseline);
        }
    }

    // =========================================================================
    // FLOW-110 — Restart under load (N concurrent contexts survive force-close)
    // =========================================================================

    /**
     * FLOW-110: the mid-saga kill contract scaled to N concurrent instances on a single
     * definition. Half are driven to {@link FlowState#PARKED} mid-flow, half are left
     * {@link FlowState#RUNNING} at a no-op continue step at the moment of force-close.
     *
     * <p>The scenario asserts, in one batch:
     * <ul>
     *   <li>every PARKED snapshot survives {@code close()} and is loadable at its park step;</li>
     *   <li>the rebuilt engine resumes every parked instance to {@link FlowState#COMPLETED};</li>
     *   <li><b>idempotency fence</b> — the pre-park step does not re-execute after resume;</li>
     *   <li><b>counter reset</b> — the rebuilt engine's {@code stats()} starts a fresh
     *       generation at zero;</li>
     *   <li><b>no orphans</b> — no generation-1 worker virtual thread survives into
     *       generation-2 (the {@code close()} interrupt+join completed).</li>
     * </ul>
     *
     * <p>N is bounded by {@link #restartLoadCount()} (default 16) and MUST stay deterministic
     * on a 2-vCPU runner — TCK-064 deadlocked a transport stress gate under thread pressure
     * on exactly such a runner; this gate keeps N small on purpose.
     */
    @Nested
    @DisplayName("Restart under load — N concurrent flows survive force-close and resume")
    class RestartUnderLoad {

        private static final String DEF_NAME = "restart-under-load-saga";
        private static final String WORKER_THREAD_PREFIX = "exeris-flow-";

        // 60s, not the suite's usual 30s: this method's own await budgets sum to 35s (10 + 5 + 15 +
        // 5), so a 30s ceiling can kill it mid-await and report an anonymous timeout instead of the
        // assertion that would name what actually went wrong.
        @Test
        @Timeout(value = 60, unit = TimeUnit.SECONDS)
        @DisplayName("N parked snapshots survive close(); all resume to COMPLETED; no re-exec, no orphans, counters reset")
        void parkedFleetSurvivesForceCloseAndResumes() {
            int n = restartLoadCount();
            assertThat(n).as("restartLoadCount() must be positive").isGreaterThan(0);

            // Per-instance execution counters keyed by instance UUID-most.
            java.util.Map<Long, AtomicInteger> step0Exec = new java.util.concurrent.ConcurrentHashMap<>();
            java.util.Map<Long, AtomicInteger> step2Exec = new java.util.concurrent.ConcurrentHashMap<>();
            // Instances selected to PARK at step 1 (the rest fall through CONTINUE at step 1).
            java.util.Set<Long> parkSet = java.util.concurrent.ConcurrentHashMap.newKeySet();

            FlowStepAction step0 = ctx -> {
                step0Exec.computeIfAbsent(ctx.instanceIdMost(), _ -> new AtomicInteger()).incrementAndGet();
                return FlowOutcome.CONTINUE;
            };
            // Step 1 is the divergence point: parked half PARKs, running half falls through.
            FlowStepAction step1 = ctx ->
                    parkSet.contains(ctx.instanceIdMost()) ? FlowOutcome.PARK : FlowOutcome.CONTINUE;
            FlowStepAction step2 = ctx -> {
                step2Exec.computeIfAbsent(ctx.instanceIdMost(), _ -> new AtomicInteger()).incrementAndGet();
                return FlowOutcome.CONTINUE;
            };

            FlowDefinition def = engine.plans().newDefinition(DEF_NAME)
                    .step("validate", step0, null)
                    .step("gate",     step1, null)
                    .step("ship",     step2, null)
                    .transition(0, 1)
                    .transition(1, 2)
                    .build();

            FlowExecutionPlan plan = engine.plans().compile(def);

            List<FlowContext> all       = new ArrayList<>(n);
            List<FlowContext> parked    = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                FlowContext ctx = TestFlowContexts.create(
                        UUID.randomUUID().toString(), DEF_NAME);
                all.add(ctx);
                if ((i & 1) == 0) {          // ~half driven to PARK
                    parkSet.add(ctx.instanceIdMost());
                    parked.add(ctx);
                }
            }

            for (FlowContext ctx : all) {
                engine.scheduler().schedule(plan, ctx);
            }

            // Wait until the whole parked fleet has checkpointed.
            assertThat(awaitCondition(() -> parked.stream().allMatch(this::parkedAtStep1), 10))
                    .as("All %d PARKED instances MUST checkpoint at step 1 before force-close", parked.size())
                    .isTrue();

            // Force-close generation 1 — simulates JVM kill. The interrupt+bounded join
            // must complete (no generation-1 worker survives into generation 2).
            engine.close();
            engine = null;

            // (a) every parked snapshot survived and is at its park step.
            for (FlowContext ctx : parked) {
                Optional<FlowSnapshot> snap = loadSnapshot(ctx);
                assertThat(snap)
                        .as("PARKED checkpoint for %s MUST survive force-close", ctx.instanceIdMost())
                        .isPresent();
                assertThat(snap.get().currentStep())
                        .as("Checkpoint for %s MUST be at step 1 (gate)", ctx.instanceIdMost())
                        .isEqualTo(1);
                assertThat(snap.get().state())
                        .as("Checkpoint for %s MUST be PARKED", ctx.instanceIdMost())
                        .isEqualTo(FlowState.PARKED);
            }

            // (e) no orphan generation-1 worker VT survives the close() join. close() joins
            // synchronously, but VT teardown after join-return is racy by a few microseconds —
            // assert it settles to zero rather than sampling a single instant.
            assertThat(awaitCondition(() -> liveWorkerThreadCount() == 0L, 5))
                    .as("No generation-1 flow worker VT (%s*) may survive close()", WORKER_THREAD_PREFIX)
                    .isTrue();

            // Rebuild generation 2 on the same store, recompile the definition, wake the fleet.
            engine = rebuildEngine();
            engine.start();

            // (d) counter reset — a fresh generation starts at zero.
            assertThat(engine.stats().completedFlows())
                    .as("Rebuilt engine completedFlows MUST start at 0 (new lifecycle generation)")
                    .isZero();
            assertThat(engine.stats().activeFlows())
                    .as("Rebuilt engine activeFlows MUST start at 0")
                    .isZero();

            engine.plans().compile(def);
            for (FlowContext ctx : parked) {
                engine.scheduler().wake(ctx);
            }

            // (b) every parked instance resumes and reaches COMPLETED. In unbounded-catalog
            // mode (the TCK default) the durable snapshot is DELETED on complete(), so the
            // observable terminal proof is twofold: the rebuilt-generation completedFlows
            // counter reaches the parked fleet size AND the checkpoint disappears from the store.
            int expectedCompleted = parked.size();
            assertThat(awaitCondition(
                    () -> engine.stats().completedFlows() >= expectedCompleted, 15))
                    .as("Rebuilt engine MUST drive all %d resumed instances to COMPLETED", expectedCompleted)
                    .isTrue();

            // The counter above is not a proxy for the reclaim. complete() increments
            // completedFlows, publishes progress, and only then deletes the checkpoint, so the
            // fleet-size gate is satisfied while the last instance's row is still in the store —
            // and reading it in the same breath makes this a race, not an assertion. Await the
            // reclaim itself; the per-instance check below still names the offender if it never
            // lands, rather than reporting an anonymous timeout.
            // 5s, not the 15s the line above uses: that one waits for sixteen flows to execute,
            // this one for a finalization step that is sub-millisecond in practice (50ms with the
            // window widened far enough to reproduce the race). Deliberately not asserted — a
            // timeout here falls through to the per-instance loop, which names the offender.
            awaitCondition(() -> allCheckpointsReclaimed(parked), 5);

            for (FlowContext ctx : parked) {
                assertThat(loadSnapshot(ctx))
                        .as("COMPLETED checkpoint for %s MUST be reclaimed from the store on complete()",
                                ctx.instanceIdMost())
                        .isEmpty();
            }

            // (c) idempotency fence — step0 (pre-park) executed exactly once per parked
            // instance across BOTH generations; the post-park step2 ran exactly once.
            for (FlowContext ctx : parked) {
                long key = ctx.instanceIdMost();
                assertThat(step0Exec.getOrDefault(key, new AtomicInteger()).get())
                        .as("step0 (validate) MUST NOT re-execute after resume for %s — idempotency fence", key)
                        .isEqualTo(1);
                assertThat(step2Exec.getOrDefault(key, new AtomicInteger()).get())
                        .as("step2 (ship) MUST execute exactly once after wake for %s", key)
                        .isEqualTo(1);
            }
        }

        private boolean parkedAtStep1(FlowContext ctx) {
            Optional<FlowSnapshot> snap = loadSnapshot(ctx);
            return snap.isPresent()
                    && snap.get().state() == FlowState.PARKED
                    && snap.get().currentStep() == 1;
        }

        private static long liveWorkerThreadCount() {
            return Thread.getAllStackTraces().keySet().stream()
                    .filter(Thread::isAlive)
                    .map(Thread::getName)
                    .filter(name -> name != null && name.startsWith(WORKER_THREAD_PREFIX))
                    .count();
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

            // give any spurious re-execution time to manifest — park carrier instead of busy-spin
            LockSupport.parkNanos(
                    TimeUnit.SECONDS.toNanos(1));
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

            // settle — park carrier instead of busy-spin
            LockSupport.parkNanos(
                    TimeUnit.SECONDS.toNanos(1));
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

            Optional<FlowSnapshot> snapshotOpt = loadSnapshot(ctx);
            Assertions.assertThat(snapshotOpt)
                    .as("After FAIL, FlowSnapshot MUST be persisted for context %s", ctx)
                    .isPresent();
            Assertions.assertThat(snapshotOpt.get().state())
                    .as("After FAIL, FlowSnapshot state MUST be COMPENSATING or FAILED_ROLLEDBACK, was: %s", snapshotOpt.get().state())
                    .isIn(FlowState.COMPENSATING, FlowState.FAILED_ROLLEDBACK);

            // settle — park carrier instead of busy-spin
            LockSupport.parkNanos(
                    TimeUnit.SECONDS.toNanos(1));
        }
    }
}
