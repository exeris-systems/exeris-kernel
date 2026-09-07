/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.tck.contract.flow;

import eu.exeris.kernel.spi.flow.FlowEngine;
import eu.exeris.kernel.spi.flow.model.FlowContext;
import eu.exeris.kernel.spi.flow.model.FlowDefinition;
import eu.exeris.kernel.spi.flow.model.FlowExecutionPlan;
import eu.exeris.kernel.spi.flow.model.FlowOutcome;
import eu.exeris.kernel.spi.flow.model.FlowStepAction;
import eu.exeris.kernel.tck.contract.AbstractSubsystemCarrierPinningTck;
import org.junit.jupiter.api.DisplayName;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * TCK: Carrier pinning verifier for the Flow step-transition hot path.
 *
 * <h2>Hot Path Under Test</h2>
 * <p>{@code scheduler.schedule(plan, ctx)} — the synchronous dispatch entry into the flow
 * engine — must never pin a carrier thread. {@code park()}/{@code wake()} are asynchronous
 * state transitions that require a prior {@code schedule()} to complete, so driving them
 * immediately afterward would race across implementations; they are exercised elsewhere, not
 * as part of this hot path.
 *
 * @since 0.5
 * @see AbstractSubsystemCarrierPinningTck
 * @see FlowZeroAllocTck
 */
@DisplayName("Flow carrier pinning TCK")
public abstract class FlowCarrierPinningTck extends AbstractSubsystemCarrierPinningTck {

    /**
     * Creates a fully configured, but not yet started, {@link FlowEngine}.
     *
     * @return a new engine instance, not yet started
     */
    protected abstract FlowEngine createEngine();

    // =========================================================================
    // State
    // =========================================================================

    /**
     * Number of pre-allocated per-VT slots — set to exactly
     * {@code warmupIterations() + hotPathIterations()} inside {@link #bootstrapSubsystem()}.
     * Declared as a non-final instance field so that static analysis tools do not flag
     * {@code i < vtSlotCount} as an always-true comparison.
     */
    private int vtSlotCount = 0;

    private FlowEngine engine;
    private FlowExecutionPlan plan;

    /** Pre-allocated per-VT FlowContexts — each VT owns exactly one slot. */
    private FlowContext[] contexts;

    /** Monotonic slot counter — each executing VT claims the next available index. */
    private final AtomicInteger vtIndex = new AtomicInteger(0);

    /**
     * Creates the contract; subclasses supply the engine via {@link #createEngine()}.
     *
     * <p>The {@code engine}, {@code plan} and {@code contexts} fields start unset and
     * {@code vtSlotCount} starts at zero — {@link #bootstrapSubsystem()} populates all four
     * before the hot-path loop runs.
     */
    public FlowCarrierPinningTck() {
        // Declared, not added: the implicit no-arg constructor, written out so it can carry a comment.
        super();
    }

    @Override protected String subsystemName()      { return "FlowEngine"; }
    @Override protected String hotPathDescription() { return "scheduler.schedule(plan, ctx)"; }

    /**
     * Returns the number of warm-up VT iterations (phase 1 — discarded).
     * Delegates to the base-class accessor so this value stays in sync.
     *
     * @return the warm-up iteration count
     */
    protected int warmupIterations() { return warmupVtCount(); }

    /**
     * Returns the number of steady-state VT iterations (phase 2 — measured).
     * Delegates to the base-class accessor so this value stays in sync.
     *
     * @return the measured steady-state iteration count
     */
    protected int hotPathIterations() { return steadyVtCount(); }

    @Override
    protected void bootstrapSubsystem() {
        engine      = createEngine();
        vtSlotCount = warmupVtCount() + steadyVtCount();
        engine.start();

        FlowStepAction noOp = _ -> FlowOutcome.CONTINUE;
        FlowDefinition def  = engine.plans().newDefinition("carrier-pin-flow")
                .step("step-a", noOp, null)
                .step("step-b", noOp, null)
                .transition(0, 1)
                .build();
        plan = engine.plans().compile(def);

        contexts = new FlowContext[vtSlotCount];
        for (int i = 0; i < vtSlotCount; i++) {
            contexts[i] = TestFlowContexts.create("carrier-pin-" + i, "carrier-pin-flow");
        }
        vtIndex.set(0);
    }

    @Override
    protected void runSingleIteration() {
        // Each VT claims its own pre-allocated FlowContext — zero allocations on the hot path.
        // Only schedule() is tested for carrier pinning: it is the synchronous dispatch
        // entry into the flow engine. park()/wake() are asynchronous state transitions
        // that require a prior schedule() to complete — calling them immediately after
        // schedule() is a race condition that makes the test flaky across implementations.
        int idx = vtIndex.getAndIncrement();
        engine.scheduler().schedule(plan, contexts[idx]);
    }

    @Override
    protected void tearDownSubsystem() {
        if (engine != null) engine.close();
    }
}
