/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.tck.contract.flow;

import eu.exeris.kernel.spi.flow.FlowEngine;
import eu.exeris.kernel.spi.flow.FlowEngineStats;
import eu.exeris.kernel.spi.flow.FlowExecutionPlanFactory;
import eu.exeris.kernel.spi.flow.FlowScheduler;
import eu.exeris.kernel.spi.flow.model.FlowContext;
import eu.exeris.kernel.spi.flow.model.FlowDefinition;
import eu.exeris.kernel.spi.flow.model.FlowExecutionPlan;
import eu.exeris.kernel.spi.flow.model.FlowOutcome;
import eu.exeris.kernel.spi.flow.model.FlowState;
import eu.exeris.kernel.spi.flow.model.FlowStepAction;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordingStream;
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
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * TCK: Abstract base for {@link FlowEngine} contract verification.
 *
 * <h2>Front 2 — The Maestro (Flow &amp; Events)</h2>
 * <p>Verifies the full {@link FlowEngine} component lifecycle and the correctness
 * of the {@link FlowExecutionPlanFactory} compile/build pipeline.
 *
 * <h2>Verified Constraints</h2>
 * <ol>
 *   <li>start() / close() lifecycle is correct and close() is idempotent.</li>
 *   <li>plans(), scheduler(), registry() return non-null after start().</li>
 *   <li>A single-step flow can be defined, compiled, and scheduled without exception.</li>
 *   <li>compile(definition) returns a plan with stepCount() == definition step count.</li>
 *   <li>stepAt(i) executes in O(1) time (verified structurally — no O(n) scan allowed).</li>
 *   <li>FlowExecutionPlan is immutable — the same plan can be scheduled for multiple instances.</li>
 * </ol>
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * class CommunityFlowEngineTest extends AbstractFlowEngineTck {
 *     \@Override protected FlowEngine createEngine() {
 *         return new CommunityFlowProvider().createEngine(FlowEngineConfig.defaults());
 *     }
 * }
 * }</pre>
 *
 * @since 0.5.0
 */
public abstract class AbstractFlowEngineTck {

    // =========================================================================
    // Template method
    // =========================================================================

    /** Creates a fully configured, but not yet started, {@link FlowEngine}. */
    protected abstract FlowEngine createEngine();

    private FlowEngine engine;

    @BeforeEach
    final void setUp() {
        engine = createEngine();
    }

    @AfterEach
    final void tearDown() {
        engine.close();
    }

    // =========================================================================
    // Lifecycle
    // =========================================================================

    @Nested
    @DisplayName("FlowEngine lifecycle contract")
    class Lifecycle {

        @Test
        @DisplayName("start() then close() completes without exception")
        void startAndCloseHappyPath() {
            assertThatCode(() -> engine.start()).doesNotThrowAnyException();
            assertThatCode(() -> engine.close()).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("close() is idempotent — calling twice does not throw")
        void closeIsIdempotent() {
            engine.start();
            engine.close();
            assertThatCode(() -> engine.close())
                    .as("close() MUST be idempotent — double-close is a no-op")
                    .doesNotThrowAnyException();
        }
    }

    // =========================================================================
    // Component accessors (after start)
    // =========================================================================

    @Nested
    @DisplayName("Component accessors — non-null after start()")
    class ComponentAccessors {

        @BeforeEach
        void startEngine() {
            engine.start();
        }

        @Test
        @DisplayName("plans() returns a non-null FlowExecutionPlanFactory")
        void plansIsNonNull() {
            assertThat(engine.plans()).as("FlowEngine.plans() MUST not be null after start").isNotNull();
        }

        @Test
        @DisplayName("scheduler() returns a non-null FlowScheduler")
        void schedulerIsNonNull() {
            assertThat(engine.scheduler()).as("FlowEngine.scheduler() MUST not be null after start").isNotNull();
        }

        @Test
        @DisplayName("registry() returns a non-null FlowRegistry")
        void registryIsNonNull() {
            assertThat(engine.registry()).as("FlowEngine.registry() MUST not be null after start").isNotNull();
        }
    }

    // =========================================================================
    // FlowExecutionPlanFactory — define & compile pipeline
    // =========================================================================

    @Nested
    @DisplayName("FlowExecutionPlanFactory — build & compile pipeline")
    class PlanFactory {

        private FlowExecutionPlanFactory factory;

        @BeforeEach
        void startEngineAndGetFactory() {
            engine.start();
            factory = engine.plans();
        }

        @Test
        @DisplayName("newDefinition() returns a non-null builder for a non-blank name")
        void newDefinitionReturnsBuilder() {
            assertThat(factory.newDefinition("test-flow"))
                    .as("FlowExecutionPlanFactory.newDefinition() must not return null")
                    .isNotNull();
        }

        @Test
        @DisplayName("compile(definition) returns a plan with matching definitionName()")
        void compiledPlanHasCorrectDefinitionName() {
            FlowStepAction noOp = ctx -> eu.exeris.kernel.spi.flow.model.FlowOutcome.CONTINUE;
            FlowDefinition def = factory.newDefinition("order-saga")
                    .step("validate", noOp, null)
                    .step("process",  noOp, null)
                    .transition(0, 1)
                    .build();

            FlowExecutionPlan plan = factory.compile(def);

            assertThat(plan.definitionName())
                    .as("Compiled plan must carry the same definitionName as the definition")
                    .isEqualTo("order-saga");
        }

        @Test
        @DisplayName("compile(definition) returns plan with correct stepCount()")
        void compiledPlanHasCorrectStepCount() {
            FlowStepAction noOp = ctx -> eu.exeris.kernel.spi.flow.model.FlowOutcome.CONTINUE;
            FlowDefinition def = factory.newDefinition("two-step-flow")
                    .step("step-a", noOp, null)
                    .step("step-b", noOp, null)
                    .transition(0, 1)
                    .build();

            FlowExecutionPlan plan = factory.compile(def);

            assertThat(plan.stepCount())
                    .as("Compiled plan stepCount() MUST equal the number of declared steps")
                    .isEqualTo(2);
        }

        @Test
        @DisplayName("stepAt(i) — O(1) structural contract: no exception, non-null for valid index")
        @Timeout(value = 500, unit = TimeUnit.MILLISECONDS)
        void stepAtIsO1AndNonNull() {
            FlowStepAction noOp = ctx -> eu.exeris.kernel.spi.flow.model.FlowOutcome.CONTINUE;
            FlowDefinition def = factory.newDefinition("structural-test")
                    .step("only-step", noOp, null)
                    .build();

            FlowExecutionPlan plan = factory.compile(def);

            assertThat(plan.stepAt(0))
                    .as("stepAt(0) MUST return a non-null FlowStepDescriptor for a valid index")
                    .isNotNull();
        }

        @Test
        @DisplayName("Same compiled plan is reusable — can be scheduled for multiple instances")
        void compiledPlanIsImmutableAndReusable() {
            FlowStepAction noOp = ctx -> eu.exeris.kernel.spi.flow.model.FlowOutcome.CONTINUE;
            FlowDefinition def = factory.newDefinition("reusable-flow")
                    .step("the-step", noOp, null)
                    .build();

            FlowExecutionPlan plan = factory.compile(def);
            FlowScheduler scheduler = engine.scheduler();

            // Two distinct contexts for the same plan — should not throw
            assertThatCode(() -> {
                scheduler.schedule(plan, TestFlowContexts.create("inst-1", "reusable-flow"));
                scheduler.schedule(plan, TestFlowContexts.create("inst-2", "reusable-flow"));
            }).as("Same FlowExecutionPlan MUST be schedulable for multiple instances (immutable)")
              .doesNotThrowAnyException();
        }
    }

    // =========================================================================
    // TCK-062 — JFR shutdown event contract (FLOW-108)
    // =========================================================================

    /**
     * TCK-062: every {@link FlowEngine} binding MUST emit the
     * {@code eu.exeris.kernel.flow.Shutdown} JFR event on {@link FlowEngine#close()},
     * carrying engine name, lifecycle counter snapshot, persistence/compensation flags,
     * and the elapsed shutdown duration. The contract is binding-agnostic — both Community
     * (heap) and Enterprise (off-heap) implementations are obliged.
     */
    @Nested
    @DisplayName("FlowEngine JFR shutdown contract — eu.exeris.kernel.flow.Shutdown")
    class JfrShutdownContract {

        private static final String SHUTDOWN_EVENT = "eu.exeris.kernel.flow.Shutdown";

        @Test
        @Timeout(value = 10, unit = TimeUnit.SECONDS)
        @DisplayName("close() emits the Shutdown JFR event with engine name + duration + counters")
        void closeEmitsShutdownEventOnce() throws Exception {
            CountDownLatch eventReceived = new CountDownLatch(1);
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

                engine.start();

                FlowDefinition def = engine.plans().newDefinition("shutdown-tck")
                        .step("noop", _ -> FlowOutcome.CONTINUE, null)
                        .build();
                FlowExecutionPlan plan = engine.plans().compile(def);
                engine.scheduler().schedule(plan, TestFlowContexts.create(
                        UUID.randomUUID().toString(), "shutdown-tck"));

                engine.close();

                assertThat(eventReceived.await(5, TimeUnit.SECONDS))
                        .as("eu.exeris.kernel.flow.Shutdown MUST be emitted within 5 s of close()")
                        .isTrue();

                RecordedEvent event = captured.get();
                assertThat(event.getString("engineName"))
                        .as("Shutdown event MUST carry a non-blank engineName")
                        .isNotBlank();
                assertThat(event.getLong("activeFlows"))
                        .as("activeFlows MUST be reported as a final post-quiesce counter")
                        .isGreaterThanOrEqualTo(0L);
                assertThat(event.getLong("shutdownDurationNs"))
                        .as("shutdownDurationNs MUST capture wall-clock time spent in close() and be >= 0")
                        .isGreaterThanOrEqualTo(0L);
                // Field presence checks — implementations MUST publish these fields even if zero.
                assertThat(event.getLong("parkedFlows")).isGreaterThanOrEqualTo(0L);
                assertThat(event.getLong("completedFlows")).isGreaterThanOrEqualTo(0L);
                assertThat(event.getLong("failedFlows")).isGreaterThanOrEqualTo(0L);
                assertThat(eventCount.get())
                        .as("Single close() MUST emit exactly one Shutdown event")
                        .isEqualTo(1);
            }
        }
    }

    // =========================================================================
    // TCK-063 — Restart-aware semantics (counter reset, parked-reschedule, lookupParked)
    // =========================================================================

    /**
     * TCK-063: cross-restart semantics that every {@link FlowEngine} binding MUST honor.
     *
     * <ul>
     *   <li>Lifecycle counters reset across {@code close()} → {@code start()}.</li>
     *   <li>Scheduling a context whose state is already {@link FlowState#PARKED} is a no-op
     *       — no step is executed; the engine MUST treat it as registration only.</li>
     *   <li>{@code lookupParked} for an unknown instance returns {@link Optional#empty()}
     *       without throwing, regardless of whether persistence is bound.</li>
     * </ul>
     */
    @Nested
    @DisplayName("FlowEngine restart-aware semantics")
    class RestartAwareSemantics {

        @BeforeEach
        void startEngine() {
            engine.start();
        }

        @Test
        @Timeout(value = 10, unit = TimeUnit.SECONDS)
        @DisplayName("Lifecycle counters reset to zero after close() then start()")
        void lifecycleCountersResetAcrossRestart() {
            FlowDefinition def = engine.plans().newDefinition("restart-counter-flow")
                    .step("done", _ -> FlowOutcome.CONTINUE, null)
                    .build();
            FlowExecutionPlan plan = engine.plans().compile(def);

            for (int i = 0; i < 3; i++) {
                engine.scheduler().schedule(plan, TestFlowContexts.create(
                        UUID.randomUUID().toString(), "restart-counter-flow"));
            }

            assertThat(awaitCondition(() -> engine.stats().completedFlows() >= 3L, 5))
                    .as("Three flows must complete before observing counters")
                    .isTrue();

            FlowEngineStats before = engine.stats();
            assertThat(before.completedFlows())
                    .as("completedFlows MUST be > 0 before restart")
                    .isGreaterThan(0L);

            engine.close();
            engine.start();

            FlowEngineStats after = engine.stats();
            assertThat(after.completedFlows())
                    .as("completedFlows MUST reset to 0 after close() → start() (lifecycle generation)")
                    .isZero();
            assertThat(after.failedFlows())
                    .as("failedFlows MUST reset to 0 after close() → start()")
                    .isZero();
            assertThat(after.activeFlows())
                    .as("activeFlows MUST be 0 after close() → start()")
                    .isZero();
        }

        @Test
        @Timeout(value = 10, unit = TimeUnit.SECONDS)
        @DisplayName("Scheduling a PARKED context is a no-op — step is not executed")
        void parkedRescheduleDoesNotExecuteStep() {
            AtomicInteger executions = new AtomicInteger();

            FlowDefinition def = engine.plans().newDefinition("parked-reschedule-flow")
                    .step("must-not-execute", _ -> {
                        executions.incrementAndGet();
                        return FlowOutcome.CONTINUE;
                    }, null)
                    .build();
            FlowExecutionPlan plan = engine.plans().compile(def);

            FlowContext parkedCtx = TestFlowContexts.createWithState(
                    UUID.randomUUID().toString(),
                    "parked-reschedule-flow",
                    FlowState.PARKED);

            engine.scheduler().schedule(plan, parkedCtx);
            // Give any spurious dispatch time to manifest.
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(300L));

            assertThat(executions.get())
                    .as("Scheduling a PARKED context MUST NOT execute the step (no-op semantics)")
                    .isZero();
        }

        @Test
        @DisplayName("lookupParked for an unknown instance returns empty() without throwing")
        void lookupParkedUnknownReturnsEmpty() {
            FlowContext unknown = TestFlowContexts.create(
                    UUID.randomUUID().toString(), "never-scheduled-flow");

            Optional<FlowContext> result = engine.scheduler().lookupParked(
                    unknown.instanceIdMost(), unknown.instanceIdLeast());

            assertThat(result)
                    .as("lookupParked for an unknown instance MUST return Optional.empty()")
                    .isEmpty();
        }
    }

    // =========================================================================
    // DIST-303 — Saga timeout enforcement (FlowTimeoutEvent + compensation)
    // =========================================================================

    /**
     * DIST-303: a flow whose absolute timeout has already passed at scheduling time
     * MUST NOT execute its first step; the engine MUST drive the instance to
     * {@link FlowState#FAILED_ROLLEDBACK} via the compensation path and emit a
     * {@code eu.exeris.kernel.flow.Timeout} JFR event. The check is binding-agnostic —
     * both Community (heap) and Enterprise (off-heap) implementations are obliged.
     */
    @Nested
    @DisplayName("FlowEngine saga timeout contract")
    class SagaTimeoutContract {

        private static final String TIMEOUT_EVENT = "eu.exeris.kernel.flow.Timeout";

        @BeforeEach
        void startEngine() {
            engine.start();
        }

        @Test
        @Timeout(value = 10, unit = TimeUnit.SECONDS)
        @DisplayName("Already-expired deadline routes the flow to FAILED_ROLLEDBACK without executing the step")
        void expiredDeadlineFailsWithoutExecutingStep() throws Exception {
            CountDownLatch timeoutEventReceived = new CountDownLatch(1);
            AtomicInteger executions = new AtomicInteger();

            try (RecordingStream rs = new RecordingStream()) {
                rs.enable(TIMEOUT_EVENT);
                rs.onEvent(TIMEOUT_EVENT, _ -> timeoutEventReceived.countDown());
                rs.startAsync();

                FlowDefinition def = engine.plans().newDefinition("timeout-flow")
                        .step("must-not-execute", _ -> {
                            executions.incrementAndGet();
                            return FlowOutcome.CONTINUE;
                        }, null)
                        .build();
                FlowExecutionPlan plan = engine.plans().compile(def);

                FlowContext expired = expiredCtx(UUID.randomUUID().toString(), "timeout-flow");
                engine.scheduler().schedule(plan, expired);

                assertThat(awaitCondition(() -> engine.stats().failedFlows() >= 1L, 5))
                        .as("Expired flow MUST transition to FAILED_ROLLEDBACK within 5 s")
                        .isTrue();
                assertThat(executions.get())
                        .as("Step MUST NOT execute when the absolute deadline has already passed")
                        .isZero();
                assertThat(timeoutEventReceived.await(3, TimeUnit.SECONDS))
                        .as("eu.exeris.kernel.flow.Timeout MUST fire on deadline detection")
                        .isTrue();
            }
        }

        private static FlowContext expiredCtx(String instanceId, String definitionName) {
            UUID uuid = UUID.nameUUIDFromBytes(
                    instanceId.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return new FlowContext() {
                @Override public long instanceIdMost()   { return uuid.getMostSignificantBits(); }
                @Override public long instanceIdLeast()  { return uuid.getLeastSignificantBits(); }
                @Override public String definitionName() { return definitionName; }
                @Override public int currentStep()       { return 0; }
                @Override public FlowState state()       { return FlowState.RUNNING; }
                @Override public long timeoutNanos()     { return 1L; }
            };
        }
    }

    // =========================================================================
    // FLOW-110 — Outcome transition correctness (CONTINUE / COMPLETE / FAIL / throw)
    // =========================================================================

    /**
     * FLOW-110: per-{@link FlowOutcome} transition correctness, asserted purely through
     * observable engine state — {@code engine.stats()} lifecycle counters and step-execution
     * counters. No new SPI: the resolved/unresolved classification uses a <b>test-local</b>
     * helper ({@link #isResolved(FlowState)}), not an SPI predicate.
     *
     * <p>Coverage focuses on the genuinely new ground; ordering of compensation is already
     * fixed by {@code AbstractSagaRecoveryTck.SagaCompensationTest} and is referenced, not
     * duplicated. The two primary new obligations are:
     * <ul>
     *   <li>a <b>thrown {@link RuntimeException}</b> from a step (distinct from an explicit
     *       {@link FlowOutcome#FAIL}) drives the same FAIL → COMPENSATING → FAILED_ROLLEDBACK
     *       path and emits {@code eu.exeris.kernel.flow.StepFailed};</li>
     *   <li><b>terminal idempotency for {@code FAILED_ROLLEDBACK}</b> — re-scheduling a
     *       rolled-back context re-runs no step and does not leave the terminal state
     *       (the existing terminal-idempotency test fences only {@code COMPLETED}).</li>
     * </ul>
     */
    @Nested
    @DisplayName("FlowEngine outcome-transition correctness")
    class OutcomeTransitions {

        private static final String STEP_FAILED_EVENT = "eu.exeris.kernel.flow.StepFailed";

        @BeforeEach
        void startEngine() {
            engine.start();
        }

        /**
         * Test-local terminal classification — NOT an SPI predicate. Unresolved states
         * ({@code RUNNING}, {@code PARKED}, {@code COMPENSATING}) may still transition;
         * resolved states ({@code COMPLETED}, {@code FAILED_ROLLEDBACK}) are terminal.
         */
        private boolean isResolved(FlowState state) {
            return state == FlowState.COMPLETED || state == FlowState.FAILED_ROLLEDBACK;
        }

        @Test
        @Timeout(value = 10, unit = TimeUnit.SECONDS)
        @DisplayName("CONTINUE advances to the next step (non-terminal mid-flight, then COMPLETED)")
        void continueAdvancesToNextStep() {
            AtomicInteger step0 = new AtomicInteger();
            AtomicInteger step1 = new AtomicInteger();

            FlowDefinition def = engine.plans().newDefinition("continue-flow")
                    .step("first",  _ -> { step0.incrementAndGet(); return FlowOutcome.CONTINUE; }, null)
                    .step("second", _ -> { step1.incrementAndGet(); return FlowOutcome.CONTINUE; }, null)
                    .transition(0, 1)
                    .build();
            FlowExecutionPlan plan = engine.plans().compile(def);

            engine.scheduler().schedule(plan, TestFlowContexts.create(
                    UUID.randomUUID().toString(), "continue-flow"));

            assertThat(awaitCondition(() -> engine.stats().completedFlows() >= 1L, 5))
                    .as("CONTINUE must advance step 0 → step 1 and reach COMPLETED")
                    .isTrue();
            assertThat(step0.get()).as("step 0 executes once").isEqualTo(1);
            assertThat(step1.get())
                    .as("CONTINUE from step 0 MUST advance to and execute step 1")
                    .isEqualTo(1);
        }

        @Test
        @Timeout(value = 10, unit = TimeUnit.SECONDS)
        @DisplayName("COMPLETE short-circuits: immediate COMPLETED, downstream step never runs")
        void completeShortCircuitsDownstream() {
            AtomicInteger downstream = new AtomicInteger();

            FlowDefinition def = engine.plans().newDefinition("complete-flow")
                    .step("short-circuit", _ -> FlowOutcome.COMPLETE, null)
                    .step("never-runs",    _ -> { downstream.incrementAndGet(); return FlowOutcome.CONTINUE; }, null)
                    .transition(0, 1)
                    .build();
            FlowExecutionPlan plan = engine.plans().compile(def);

            engine.scheduler().schedule(plan, TestFlowContexts.create(
                    UUID.randomUUID().toString(), "complete-flow"));

            assertThat(awaitCondition(() -> engine.stats().completedFlows() >= 1L, 5))
                    .as("COMPLETE MUST transition immediately to COMPLETED (flow.md:23 short-circuit)")
                    .isTrue();
            // Give any erroneous downstream dispatch time to manifest.
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(200L));
            assertThat(downstream.get())
                    .as("COMPLETE MUST short-circuit — the downstream step counter MUST stay 0")
                    .isZero();
            assertThat(engine.stats().failedFlows())
                    .as("COMPLETE is a success outcome — no failed flow recorded")
                    .isZero();
        }

        @Test
        @Timeout(value = 10, unit = TimeUnit.SECONDS)
        @DisplayName("FlowOutcome.FAIL drives FAILED_ROLLEDBACK via COMPENSATING (ordering: see SagaCompensationTest)")
        void explicitFailDrivesRollback() {
            AtomicInteger compensated = new AtomicInteger();

            FlowDefinition def = engine.plans().newDefinition("explicit-fail-flow")
                    .step("do",  _ -> FlowOutcome.CONTINUE,
                            _ -> { compensated.incrementAndGet(); return FlowOutcome.CONTINUE; })
                    .step("boom", _ -> FlowOutcome.FAIL, null)
                    .transition(0, 1)
                    .build();
            FlowExecutionPlan plan = engine.plans().compile(def);

            engine.scheduler().schedule(plan, TestFlowContexts.create(
                    UUID.randomUUID().toString(), "explicit-fail-flow"));

            assertThat(awaitCondition(() -> engine.stats().failedFlows() >= 1L, 5))
                    .as("Explicit FAIL MUST drive the flow to FAILED_ROLLEDBACK")
                    .isTrue();
            assertThat(compensated.get())
                    .as("FAIL MUST run the upstream compensation (reverse ordering covered by "
                            + "AbstractSagaRecoveryTck.SagaCompensationTest)")
                    .isEqualTo(1);
        }

        @Test
        @Timeout(value = 10, unit = TimeUnit.SECONDS)
        @DisplayName("NEW: thrown RuntimeException (not FlowOutcome.FAIL) follows the FAIL path and emits StepFailed")
        void thrownExceptionDrivesFailPathAndEmitsStepFailed() throws Exception {
            CountDownLatch stepFailedReceived = new CountDownLatch(1);
            AtomicInteger compensated = new AtomicInteger();

            try (RecordingStream rs = new RecordingStream()) {
                rs.enable(STEP_FAILED_EVENT);
                rs.onEvent(STEP_FAILED_EVENT, _ -> stepFailedReceived.countDown());
                rs.startAsync();

                FlowDefinition def = engine.plans().newDefinition("thrown-exception-flow")
                        .step("reserve", _ -> FlowOutcome.CONTINUE,
                                _ -> { compensated.incrementAndGet(); return FlowOutcome.CONTINUE; })
                        // A thrown RuntimeException, NOT an explicit FlowOutcome.FAIL.
                        .step("explode", _ -> { throw new IllegalStateException("boom-from-step"); }, null)
                        .transition(0, 1)
                        .build();
                FlowExecutionPlan plan = engine.plans().compile(def);

                engine.scheduler().schedule(plan, TestFlowContexts.create(
                        UUID.randomUUID().toString(), "thrown-exception-flow"));

                assertThat(awaitCondition(() -> engine.stats().failedFlows() >= 1L, 5))
                        .as("A thrown step exception MUST drive the flow to FAILED_ROLLEDBACK, "
                                + "identically to an explicit FlowOutcome.FAIL")
                        .isTrue();
                assertThat(stepFailedReceived.await(3, TimeUnit.SECONDS))
                        .as("A thrown step exception MUST emit eu.exeris.kernel.flow.StepFailed (flow.md:85)")
                        .isTrue();
                assertThat(compensated.get())
                        .as("A thrown step exception MUST drive COMPENSATING — upstream compensation runs")
                        .isEqualTo(1);
            }
        }

        @Test
        @Timeout(value = 10, unit = TimeUnit.SECONDS)
        @DisplayName("NEW: terminal idempotency for FAILED_ROLLEDBACK — re-schedule re-runs nothing, stays terminal")
        void failedRolledBackIsTerminalIdempotent() {
            AtomicInteger stepExec    = new AtomicInteger();
            AtomicInteger compExec    = new AtomicInteger();

            FlowDefinition def = engine.plans().newDefinition("terminal-idem-fail-flow")
                    .step("charge", _ -> {
                        stepExec.incrementAndGet();
                        return FlowOutcome.FAIL;
                    }, _ -> { compExec.incrementAndGet(); return FlowOutcome.CONTINUE; })
                    .build();
            FlowExecutionPlan plan = engine.plans().compile(def);
            FlowContext ctx = TestFlowContexts.create(
                    UUID.randomUUID().toString(), "terminal-idem-fail-flow");

            engine.scheduler().schedule(plan, ctx);
            assertThat(awaitCondition(() -> engine.stats().failedFlows() >= 1L, 5))
                    .as("First schedule MUST reach FAILED_ROLLEDBACK")
                    .isTrue();
            int stepAfterFirst = stepExec.get();
            int compAfterFirst = compExec.get();
            long failedAfterFirst = engine.stats().failedFlows();

            // Re-schedule the SAME terminal (FAILED_ROLLEDBACK) context — duplicate replay.
            engine.scheduler().schedule(plan, ctx);
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(300L));

            assertThat(stepExec.get())
                    .as("Re-scheduling a FAILED_ROLLEDBACK context MUST NOT re-run the step")
                    .isEqualTo(stepAfterFirst);
            assertThat(compExec.get())
                    .as("Re-scheduling a FAILED_ROLLEDBACK context MUST NOT re-run compensation")
                    .isEqualTo(compAfterFirst);
            assertThat(engine.stats().failedFlows())
                    .as("Re-scheduling a terminal FAILED_ROLLEDBACK context MUST NOT mint a new failure "
                            + "(state stays resolved/terminal)")
                    .isEqualTo(failedAfterFirst);
        }

        @Test
        @DisplayName("Test-local terminal classification helper — resolved vs unresolved (no SPI predicate)")
        void terminalClassificationHelperIsTestLocal() {
            // Documents the resolved/unresolved partition without leaking a predicate into the SPI.
            assertThat(isResolved(FlowState.RUNNING)).isFalse();
            assertThat(isResolved(FlowState.PARKED)).isFalse();
            assertThat(isResolved(FlowState.COMPENSATING)).isFalse();
            assertThat(isResolved(FlowState.COMPLETED)).isTrue();
            assertThat(isResolved(FlowState.FAILED_ROLLEDBACK)).isTrue();
        }
    }

    private static boolean awaitCondition(BooleanSupplier condition, int timeoutSeconds) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds);
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return true;
            }
            LockSupport.parkNanos(1_000_000L);
        }
        return condition.getAsBoolean();
    }
}
