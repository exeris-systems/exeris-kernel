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

    private FlowEngine        engine;
    private FlowExecutionPlan plan;
    private eu.exeris.kernel.spi.flow.model.FlowContext testCtx;

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
        plan    = engine.plans().compile(def);
        testCtx = TestFlowContexts.create("carrier-pin-steady", "carrier-pin-flow");
    }

    @Override
    protected void runSingleIteration() {
        engine.scheduler().schedule(plan, testCtx);
        engine.scheduler().park(testCtx);
        engine.scheduler().wake(testCtx);
    }

    @Override
    protected void tearDownSubsystem() {
        if (engine != null) engine.close();
    }
}

