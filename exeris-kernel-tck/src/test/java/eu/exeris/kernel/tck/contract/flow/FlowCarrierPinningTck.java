/*
 * Copyright (C) 2025-2026 Exeris. All rights reserved.
 *
 * This code is part of the Exeris Systems.
 * Distributed under the proprietary Exeris Software License.
 * Unauthorized copying or distribution is prohibited.
 */
package eu.exeris.kernel.tck.contract.flow;

import eu.exeris.kernel.spi.flow.FlowEngine;
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
 * <p>{@code scheduler.schedule(plan, ctx) → park(ctx) → wake(ctx)} — the step dispatch
 * loop must never pin a carrier thread.
 *
 * @since 0.5.0
 * @see AbstractSubsystemCarrierPinningTck
 * @see FlowZeroAllocTck
 */
@DisplayName("Flow carrier pinning TCK")
public abstract class FlowCarrierPinningTck extends AbstractSubsystemCarrierPinningTck {

    protected abstract FlowEngine createEngine();

    // =========================================================================
    // State
    // =========================================================================

    /** Maximum number of concurrent VTs across warm-up + steady phases. */
    private static final int MAX_VT_SLOTS = 10_000;

    private FlowEngine engine;
    private FlowExecutionPlan plan;

    /** Pre-allocated per-VT FlowContexts — each VT owns exactly one slot. */
    private eu.exeris.kernel.spi.flow.model.FlowContext[] contexts;

    /** Monotonic slot counter — each executing VT claims the next available index. */
    private final AtomicInteger vtIndex = new AtomicInteger(0);

    @Override protected String subsystemName()      { return "FlowEngine"; }
    @Override protected String hotPathDescription() { return "scheduler.schedule(plan, ctx) → park → wake"; }

    @Override
    protected void bootstrapSubsystem() {
        engine = createEngine();
        engine.start();

        FlowStepAction noOp = _ -> FlowOutcome.CONTINUE;
        FlowDefinition def  = engine.plans().newDefinition("carrier-pin-flow")
                .step("step-a", noOp, null)
                .step("step-b", noOp, null)
                .transition(0, 1)
                .build();
        plan = engine.plans().compile(def);

        contexts = new eu.exeris.kernel.spi.flow.model.FlowContext[MAX_VT_SLOTS];
        for (int i = 0; i < MAX_VT_SLOTS; i++) {
            contexts[i] = TestFlowContexts.create("carrier-pin-" + i, "carrier-pin-flow");
        }
        vtIndex.set(0);
    }

    @Override
    protected void runSingleIteration() {
        // Each VT claims its own pre-allocated FlowContext — zero allocations on the hot path.
        int idx = vtIndex.getAndIncrement();
        eu.exeris.kernel.spi.flow.model.FlowContext ctx = contexts[idx];
        engine.scheduler().schedule(plan, ctx);
        engine.scheduler().park(ctx);
        engine.scheduler().wake(ctx);
    }

    @Override
    protected void tearDownSubsystem() {
        if (engine != null) engine.close();
    }
}

