/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.tck.contract.flow;

import eu.exeris.kernel.spi.flow.FlowEngine;
import eu.exeris.kernel.spi.flow.FlowScheduler;
import eu.exeris.kernel.spi.flow.model.FlowContext;
import eu.exeris.kernel.spi.flow.model.FlowDefinition;
import eu.exeris.kernel.spi.flow.model.FlowExecutionPlan;
import eu.exeris.kernel.spi.flow.model.FlowOutcome;
import eu.exeris.kernel.spi.flow.model.FlowState;
import eu.exeris.kernel.spi.flow.model.FlowStepAction;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import eu.exeris.kernel.tck.support.TckScope;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * TCK: Abstract base for {@link FlowScheduler} contract verification.
 *
 * <h2>Front 2 — The Maestro (Flow)</h2>
 * <p>The <b>critical</b> test in this suite is {@code parentCancellationPropagatesOrphanFree}:
 * it schedules tasks inside a {@code StructuredTaskScope}, then cancels the scope
 * and asserts that <em>no orphaned Virtual Threads survive</em> after {@code close()}.
 *
 * @since 0.5.0
 */
public abstract class AbstractFlowSchedulerTck {

    /** Generous: this waits on a real flow resuming, not on a poll interval. */
    private static final long WAKE_TIMEOUT_SECONDS = 30L;

    protected abstract FlowEngine createEngine();

    /** Override to reduce scope for ring-buffer-bounded Enterprise implementations. */
    protected int avalancheScheduleCount() { return 10_000; }

    private FlowEngine        engine;
    private FlowExecutionPlan sharedPlan;

    @BeforeEach
    final void setUp() {
        engine = createEngine();
        engine.start();
        FlowStepAction noOp = ctx -> FlowOutcome.CONTINUE;
        FlowDefinition def = engine.plans().newDefinition("scheduler-tck-flow")
                .step("step-one", noOp, null)
                .step("step-two", noOp, null)
                .transition(0, 1)
                .build();
        sharedPlan = engine.plans().compile(def);
    }

    @AfterEach
    final void tearDown() { engine.close(); }

    // =========================================================================
    // Basic scheduling
    // =========================================================================

    @Nested
    @DisplayName("Basic schedule / park / wake contract")
    class BasicScheduling {

        @Test
        @DisplayName("schedule() with valid plan and context does not throw")
        void scheduleHappyPath() {
            FlowContext ctx = TestFlowContexts.create("basic-sched-1", "scheduler-tck-flow");
            assertThatCode(() -> engine.scheduler().schedule(sharedPlan, ctx))
                    .as("schedule() with valid inputs MUST not throw")
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("park() followed by wake() re-schedules the context without exception")
        void parkAndWakeRoundTrip() {
            FlowScheduler scheduler = engine.scheduler();
            FlowContext ctx = TestFlowContexts.create("park-wake-1", "scheduler-tck-flow");
            assertThatCode(() -> {
                scheduler.schedule(sharedPlan, ctx);
                scheduler.park(ctx);
                scheduler.wake(ctx);
            }).as("park() then wake() MUST not throw for a validly scheduled context")
              .doesNotThrowAnyException();
        }

        /**
         * A wake refused because a run still holds the instance MUST still be honoured once that
         * run drains.
         *
         * <p>{@link FlowScheduler#wake} promises to re-submit the flow for execution, and expressly
         * allows an implementation to tolerate the immediate schedule/park/wake window rather than
         * throwing. Tolerating is not discarding: a choreography wake is one event per business
         * trigger, not a poll, so a request dropped here strands the saga — and because the instance
         * stays discoverable through {@code lookupParked}, no miss-path telemetry marks the loss.
         * Enterprise's ring-buffer scheduler has the same window on its CAS-enqueue path.
         */
        @Test
        @Timeout(value = 60, unit = TimeUnit.SECONDS)
        @DisplayName("a key-addressed wake delivered before the park still resumes the saga")
        void keyAddressedWakeBeforeParkIsNotLost() {
            FlowScheduler scheduler = engine.scheduler();
            CountDownLatch insideStep = new CountDownLatch(1);
            CountDownLatch releaseStep = new CountDownLatch(1);
            CountDownLatch resumed = new CountDownLatch(1);

            FlowDefinition definition = engine.plans().newDefinition("key-wake-before-park-flow")
                    .step("call-out", _ -> {
                        insideStep.countDown();
                        awaitQuietly(releaseStep);
                        return FlowOutcome.PARK;
                    }, null)
                    .step("settle", _ -> {
                        resumed.countDown();
                        return FlowOutcome.CONTINUE;
                    }, null)
                    .transition(0, 1)
                    .build();
            FlowExecutionPlan plan = engine.plans().compile(definition);
            FlowContext ctx = TestFlowContexts.create("key-wake-before-park-1", definition.name());

            scheduler.schedule(plan, ctx);
            assertThat(awaitQuietly(insideStep))
                    .as("the run must be in flight, so the wake precedes the park it answers")
                    .isTrue();

            // The callback lands here. lookupParked reports a running instance absent by design,
            // so the two-call form would drop this wake and nothing would re-send it.
            scheduler.wake(ctx.instanceIdMost(), ctx.instanceIdLeast());
            releaseStep.countDown();

            assertThat(awaitQuietly(resumed))
                    .as("a choreography wake is one event per business trigger: an implementation "
                        + "that drops it because the instance had not parked yet strands the saga")
                    .isTrue();
        }

        @Test
        @Timeout(value = 60, unit = TimeUnit.SECONDS)
        @DisplayName("a wake arriving while a run is still in flight is honoured, not dropped")
        void wakeDuringRunIsNotLost() {
            FlowScheduler scheduler = engine.scheduler();
            CountDownLatch insideStep = new CountDownLatch(1);
            CountDownLatch releaseStep = new CountDownLatch(1);

            FlowDefinition definition = engine.plans().newDefinition("wake-during-run-flow")
                    .step("gate", _ -> {
                        insideStep.countDown();
                        awaitQuietly(releaseStep);
                        return FlowOutcome.CONTINUE;
                    }, null)
                    .step("after-gate", _ -> FlowOutcome.CONTINUE, null)
                    .transition(0, 1)
                    .build();
            FlowExecutionPlan plan = engine.plans().compile(definition);
            FlowContext ctx = TestFlowContexts.create("wake-during-run-1", definition.name());

            scheduler.schedule(plan, ctx);
            assertThat(awaitQuietly(insideStep))
                    .as("the run must be in flight before park/wake, or this asserts nothing")
                    .isTrue();

            // The step is held on a latch, so the instance is provably still owned by a run when
            // the wake below lands. No timing assumption — this is the window a scheduler may
            // legitimately refuse to schedule into, and the one it must not lose the request in.
            scheduler.park(ctx);
            scheduler.wake(ctx);
            releaseStep.countDown();

            awaitTrue(WAKE_TIMEOUT_SECONDS, () -> engine.stats().completedFlows() >= 1);
        }

        /**
         * A flow resumed by a deferred wake MUST stop being reported as parked.
         *
         * <p>Distinct from the case above, which resumes a flow parked from outside: here the step
         * itself returns {@code PARK} on the very run that already carries a deferred wake, so the
         * park registration is taken <em>inside</em> the draining run and the resume follows it
         * immediately. Miss that shed and the instance runs while {@code lookupParked} still hands
         * it out and {@code parkedFlows} still counts it — a scheduler would hand the same instance
         * to a second wake.
         */
        @Test
        @Timeout(value = 60, unit = TimeUnit.SECONDS)
        @DisplayName("a flow that parks itself while a wake is deferred is not left registered as parked")
        void deferredWakeAfterSelfParkClearsParkedRegistration() {
            FlowScheduler scheduler = engine.scheduler();
            CountDownLatch insideStep = new CountDownLatch(1);
            CountDownLatch releaseStep = new CountDownLatch(1);

            CountDownLatch insideResumedStep = new CountDownLatch(1);
            CountDownLatch releaseResumedStep = new CountDownLatch(1);

            FlowDefinition definition = engine.plans().newDefinition("self-park-deferred-wake-flow")
                    .step("gate-then-park", _ -> {
                        insideStep.countDown();
                        awaitQuietly(releaseStep);
                        return FlowOutcome.PARK;
                    }, null)
                    // Held open so the assertions below run while the resumed flow is live. Checked
                    // after completion they prove nothing: lookupParked consults the terminal
                    // catalogue first and returns empty for a finished flow however the parked index
                    // looks, and completion clears the registration anyway.
                    .step("after-park", _ -> {
                        insideResumedStep.countDown();
                        awaitQuietly(releaseResumedStep);
                        return FlowOutcome.CONTINUE;
                    }, null)
                    .transition(0, 1)
                    .build();
            FlowExecutionPlan plan = engine.plans().compile(definition);

            // Same instance id, two views: schedule() must see a runnable context, while wake()
            // declares the parked intent that lets it resolve an instance a run still owns.
            FlowContext running = TestFlowContexts.create("self-park-wake-1", definition.name());
            FlowContext parked = TestFlowContexts.createWithState(
                    "self-park-wake-1", definition.name(), FlowState.PARKED);

            scheduler.schedule(plan, running);
            assertThat(awaitQuietly(insideStep))
                    .as("the run must be in flight before the wake, or nothing is deferred")
                    .isTrue();

            scheduler.wake(parked);
            releaseStep.countDown();

            assertThat(awaitQuietly(insideResumedStep))
                    .as("the deferred wake must resume the flow past the step that parked it")
                    .isTrue();

            assertThat(scheduler.lookupParked(parked.instanceIdMost(), parked.instanceIdLeast()))
                    .as("this instance is running right now; handing it out as parked would let a "
                            + "second wake schedule the same flow again")
                    .isEmpty();
            assertThat(engine.stats().parkedFlows())
                    .as("the park registration taken inside the run must be shed when the deferred "
                            + "wake resumes it, or the parked-flow gauge drifts up for good")
                    .isZero();

            releaseResumedStep.countDown();
            awaitTrue(WAKE_TIMEOUT_SECONDS, () -> engine.stats().completedFlows() >= 1);
        }
    }

    private static void awaitTrue(long timeoutSeconds, BooleanSupplier condition) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError(
                        "a wake delivered while a run was in flight never resumed the flow");
            }
            LockSupport.parkNanos(10_000_000L);
        }
    }

    private static boolean awaitQuietly(CountDownLatch latch) {
        try {
            return latch.await(WAKE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    // =========================================================================
    // Virtual Thread Avalanche — concurrency stress
    // =========================================================================

    @Nested
    @DisplayName("Virtual Thread avalanche — concurrency stress")
    class VirtualThreadAvalanche {

        @Test
        @DisplayName("N concurrent VT schedules complete without deadlock")
        @Timeout(value = 30, unit = TimeUnit.SECONDS)
        void concurrentSchedulesDoNotDeadlock() throws Exception {
            int count = avalancheScheduleCount();
            AtomicInteger submitted = new AtomicInteger(0);

            // awaitAllSuccessfulOrThrow — fails fast if any subtask throws
            try (TckScope scope = TckScope.openFailFast()) {
                for (int i = 0; i < count; i++) {
                    int idx = i;
                    scope.fork(() -> {
                        FlowContext ctx = TestFlowContexts.create(
                                "vt-" + idx, "scheduler-tck-flow");
                        engine.scheduler().schedule(sharedPlan, ctx);
                        submitted.incrementAndGet();
                        return null;
                    });
                }
                scope.join();
            }

            assertThat(submitted.get())
                    .as("All %d VT schedule() calls MUST complete", count)
                    .isEqualTo(count);
        }

        @Test
        @DisplayName("CRITICAL: Parent VT scope cancel — all children interrupted, zero orphaned VTs")
        @Timeout(value = 15, unit = TimeUnit.SECONDS)
        void parentCancellationPropagatesOrphanFree() throws Exception {
            // Strategy: use Joiner.allUntil(predicate) — the predicate cancels the scope
            // the moment it sees the first FAILED subtask (our 51st "bomb" task).
            // After scope.close() returns, every forked VT is guaranteed dead.
            // We verify that the 50 "waiting" tasks never reached the "completed" line.

            CountDownLatch allStarted   = new CountDownLatch(50);
            CountDownLatch cancelSignal = new CountDownLatch(1); // never released — tasks park here
            List<String>  completedIds = new CopyOnWriteArrayList<>();
            AtomicBoolean bombFired    = new AtomicBoolean(false);

            // Cancel-on-failure: the 50 parked tasks must be torn down when the bomb throws.
            try (TckScope scope = TckScope.openCancelOnFailure()) {

                // 50 "long-running" parked tasks
                for (int i = 0; i < 50; i++) {
                    int idx = i;
                    scope.fork(() -> {
                        allStarted.countDown();
                        try {
                            cancelSignal.await(10, TimeUnit.SECONDS); // parks here
                            completedIds.add("completed-" + idx);     // must NOT be reached
                        } catch (InterruptedException _) {
                            Thread.currentThread().interrupt(); // expected on cancel
                        }
                        return null;
                    });
                }

                // 51st "bomb" — waits for all 50 to park, then fails → triggers scope cancel
                scope.fork(() -> {
                    allStarted.await(5, TimeUnit.SECONDS);
                    bombFired.set(true);
                    throw new RuntimeException("Forced cancel — simulating parent scope failure");
                });

                try {
                    scope.join(); // returns once joiner cancels scope (bomb fires)
                } catch (InterruptedException _) {
                    Thread.currentThread().interrupt();
                }
                // StructuredTaskScope.FailedException is NOT thrown here because
                // allUntil.result() returns the list — it does not rethrow.
            }
            // scope.close() has returned — ALL 51 VTs are now dead.

            assertThat(bombFired.get())
                    .as("Bomb task must have fired to trigger scope cancellation")
                    .isTrue();

            assertThat(completedIds)
                    .as("After scope cancellation, NONE of the 50 parked tasks should have " +
                        "reached 'completed' state. Found: %s — this means orphaned VTs " +
                        "continued executing after scope.close(), which violates the " +
                        // CHECKSTYLE:OFF: this string NAMES the banned type as the thing the contract forbids;
                        //                 the L0 regex cannot tell a message about a ban from a use of it.
                        "Structured Concurrency contract (banned: ExecutorService/ThreadLocal).",
                        // CHECKSTYLE:ON
                        completedIds)
                    .isEmpty();
        }
    }
}
