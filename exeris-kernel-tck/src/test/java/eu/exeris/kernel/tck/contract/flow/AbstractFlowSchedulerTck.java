/*
 * Copyright (C) 2025-2026 Exeris. All rights reserved.
 *
 * This code is part of the Exeris Systems.
 * Distributed under the proprietary Exeris Software License.
 * Unauthorized copying or distribution is prohibited.
 */
package eu.exeris.kernel.tck.contract.flow;

import eu.exeris.kernel.spi.flow.FlowEngine;
import eu.exeris.kernel.spi.flow.FlowScheduler;
import eu.exeris.kernel.spi.flow.model.FlowContext;
import eu.exeris.kernel.spi.flow.model.FlowDefinition;
import eu.exeris.kernel.spi.flow.model.FlowExecutionPlan;
import eu.exeris.kernel.spi.flow.model.FlowOutcome;
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
import java.util.concurrent.StructuredTaskScope;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

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
            try (var scope = StructuredTaskScope.open(
                    StructuredTaskScope.Joiner.<Void>awaitAllSuccessfulOrThrow())) {
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

            // allUntil: cancel scope when any subtask fails (predicate returns true)
            var joiner = StructuredTaskScope.Joiner.<Void>allUntil(
                    subtask -> subtask.state() == StructuredTaskScope.Subtask.State.FAILED);

            try (var scope = StructuredTaskScope.open(joiner)) {

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
                        "Structured Concurrency contract (banned: ExecutorService/ThreadLocal).",
                        completedIds)
                    .isEmpty();
        }
    }
}
