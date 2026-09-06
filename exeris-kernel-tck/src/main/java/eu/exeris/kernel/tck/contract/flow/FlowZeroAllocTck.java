/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.tck.contract.flow;

import eu.exeris.kernel.spi.flow.FlowEngine;
import eu.exeris.kernel.spi.flow.model.FlowDefinition;
import eu.exeris.kernel.spi.flow.model.FlowExecutionPlan;
import eu.exeris.kernel.spi.flow.model.FlowOutcome;
import eu.exeris.kernel.spi.flow.model.FlowStepAction;
import eu.exeris.kernel.tck.contract.AbstractSubsystemZeroAllocTck;
import org.junit.jupiter.api.DisplayName;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;

/**
 * TCK: JFR-based zero-allocation verifier for the Flow scheduler submission hot path.
 *
 * <h2>Front 2 — FlowZeroAllocTck (Enterprise SLO)</h2>
 * <p>Proves that the measured steady-state path — repeated
 * {@link FlowEngine#scheduler()} submission of pre-allocated unique contexts through a
 * simple A → B flow — does <b>not</b> allocate {@code eu.exeris.*} objects on the heap
 * for tiers that advertise a strict zero-GC contract.
 *
 * <h2>How it works</h2>
 * <p>Extends {@link AbstractSubsystemZeroAllocTck}, which runs the JFR three-phase
 * protocol (bootstrap → warm-up → steady-state). During steady-state, this test exercises
 * a small bounded end-to-end A → B execution path after submission so the scheduler remains
 * hot without reintroducing the earlier immediate park/wake race.
 *
 * <h2>Community vs Enterprise</h2>
 * <ul>
 *   <li>Community: {@link #supportsZeroGcHotPath()} returns {@code false} —
 *       bounded heap allocations are expected and the test is advisory only.</li>
 *   <li>Enterprise: {@link #supportsZeroGcHotPath()} returns {@code true} —
 *       any {@code eu.exeris.*} allocation on the measured scheduler hot path fails the build.</li>
 * </ul>
 *
 * @since 0.5
 * @see AbstractSubsystemZeroAllocTck
 */
@DisplayName("Flow step-transition JFR zero-allocation TCK")
public abstract class FlowZeroAllocTck extends AbstractSubsystemZeroAllocTck {

    // =========================================================================
    // Template method — supply the engine
    // =========================================================================

    /**
     * Creates a fully configured, but not yet started, {@link FlowEngine}.
     *
     * @return a new engine instance, not yet started
     */
    protected abstract FlowEngine createEngine();

    // =========================================================================
    // AbstractSubsystemZeroAllocTck bindings
    // =========================================================================

    private static final int MAX_IN_FLIGHT = 8;

    private FlowEngine        engine;
    private FlowExecutionPlan plan;
    /** Pre-allocated per-iteration contexts — avoids hot-path fixture allocation while keeping instances unique. */
    private eu.exeris.kernel.spi.flow.model.FlowContext[] testContexts;
    private AtomicInteger inFlight;
    private int contextIndex;

    @Override
    protected String subsystemName() {
        return "FlowEngine";
    }

    @Override
    protected String hotPathDescription() {
        return "scheduler.schedule(plan, ctx) with bounded in-flight submission — Saga step A→B transition";
    }

    @Override
    protected void bootstrapSubsystem() {
        engine = createEngine();
        engine.start();

        inFlight = new AtomicInteger();
        FlowStepAction firstStep = ctx -> FlowOutcome.CONTINUE;
        FlowStepAction secondStep = ctx -> {
            inFlight.decrementAndGet();
            return FlowOutcome.COMPLETE;
        };
        FlowDefinition def = engine.plans().newDefinition("zero-alloc-flow")
                .step("step-a", firstStep, null)
                .step("step-b", secondStep, null)
                .transition(0, 1)
                .build();
        plan = engine.plans().compile(def);

        int totalIterations = warmupIterations() + hotPathIterations();
        testContexts = new eu.exeris.kernel.spi.flow.model.FlowContext[totalIterations];
        for (int i = 0; i < totalIterations; i++) {
            testContexts[i] = TestFlowContexts.create("zero-alloc-steady-" + i, "zero-alloc-flow");
        }
        contextIndex = 0;
    }

    @Override
    protected void runSingleIteration() {
        while (inFlight.get() >= MAX_IN_FLIGHT) {
            LockSupport.parkNanos(1L);
        }

        inFlight.incrementAndGet();
        try {
            engine.scheduler().schedule(plan, testContexts[contextIndex++]);
        } catch (RuntimeException ex) {
            inFlight.decrementAndGet();
            throw ex;
        }
    }

    @Override
    protected void tearDownSubsystem() {
        if (inFlight != null) {
            int spins = 0;
            while (inFlight.get() > 0 && spins++ < 10_000) {
                Thread.onSpinWait();
            }
        }
        if (engine != null) {
            engine.close();
        }
    }
}
