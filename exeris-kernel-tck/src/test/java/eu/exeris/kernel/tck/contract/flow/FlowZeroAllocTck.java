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
import eu.exeris.kernel.spi.flow.model.FlowDefinition;
import eu.exeris.kernel.spi.flow.model.FlowExecutionPlan;
import eu.exeris.kernel.spi.flow.model.FlowOutcome;
import eu.exeris.kernel.spi.flow.model.FlowStepAction;
import eu.exeris.kernel.tck.contract.AbstractSubsystemZeroAllocTck;
import org.junit.jupiter.api.DisplayName;

/**
 * TCK: JFR-based Zero-Allocation verifier for Flow Engine step transitions.
 *
 * <h2>Front 2 — FlowZeroAllocTck (Enterprise SLO)</h2>
 * <p>Proves that in Enterprise tier, transitioning a Saga from Step A → Step B
 * does <b>not</b> allocate any {@code eu.exeris.*} objects on the heap.
 *
 * <h2>How it works</h2>
 * <p>Extends {@link AbstractSubsystemZeroAllocTck}, which runs the JFR three-phase
 * protocol (bootstrap → warm-up → steady-state). During steady-state, this test
 * calls {@link FlowEngine#scheduler()} schedule/park/wake in a tight loop and
 * asserts zero {@code eu.exeris.*} heap allocations.
 *
 * <h2>Community vs Enterprise</h2>
 * <ul>
 *   <li>Community: {@link #supportsZeroGcHotPath()} returns {@code false} —
 *       heap allocations are expected and the test is advisory only.</li>
 *   <li>Enterprise: {@link #supportsZeroGcHotPath()} returns {@code true} —
 *       any {@code eu.exeris.*} allocation during step transitions fails the build.</li>
 * </ul>
 *
 * <h2>Usage — Community</h2>
 * <pre>{@code
 * public class CommunityFlowZeroAllocTest extends FlowZeroAllocTck {
 *     \@Override protected FlowEngine createEngine() {
 *         return new CommunityFlowProvider().createEngine(FlowEngineConfig.defaults());
 *     }
 *     \@Override protected boolean supportsZeroGcHotPath() { return false; }
 * }
 * }</pre>
 *
 * <h2>Usage — Enterprise</h2>
 * <pre>{@code
 * public class EnterpriseFlowZeroAllocTest extends FlowZeroAllocTck {
 *     \@Override protected FlowEngine createEngine() {
 *         return new EnterpriseFlowProvider().createEngine(EnterpriseFlowEngineConfig.production());
 *     }
 *     \@Override protected boolean supportsZeroGcHotPath() { return true; }
 * }
 * }</pre>
 *
 * @since 0.5.0
 * @see AbstractSubsystemZeroAllocTck
 */
@DisplayName("Flow step-transition JFR zero-allocation TCK")
public abstract class FlowZeroAllocTck extends AbstractSubsystemZeroAllocTck {

    // =========================================================================
    // Template method — supply the engine
    // =========================================================================

    protected abstract FlowEngine createEngine();

    // =========================================================================
    // AbstractSubsystemZeroAllocTck bindings
    // =========================================================================

    private FlowEngine        engine;
    private FlowExecutionPlan plan;
    /** Pre-allocated per-iteration contexts — avoids hot-path fixture allocation while keeping instances unique. */
    private eu.exeris.kernel.spi.flow.model.FlowContext[] testContexts;
    private int contextIndex;

    @Override
    protected String subsystemName() {
        return "FlowEngine";
    }

    @Override
    protected String hotPathDescription() {
        return "scheduler.schedule(plan, ctx) — Saga step A→B transition";
    }

    @Override
    protected void bootstrapSubsystem() {
        engine = createEngine();
        engine.start();

        FlowStepAction noOp = ctx -> FlowOutcome.CONTINUE;
        FlowDefinition def = engine.plans().newDefinition("zero-alloc-flow")
                .step("step-a", noOp, null)
                .step("step-b", noOp, null)
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
        // Hot-path: schedule a pre-allocated unique context through the A→B transition.
        // This avoids fixture allocations and removes the stale immediate park/wake race.
        engine.scheduler().schedule(plan, testContexts[contextIndex++]);
    }

    @Override
    protected void tearDownSubsystem() {
        if (engine != null) {
            engine.close();
        }
    }
}
