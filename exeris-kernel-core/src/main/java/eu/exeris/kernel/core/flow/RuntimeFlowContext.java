/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.core.flow;

import eu.exeris.kernel.spi.flow.model.FlowContext;
import eu.exeris.kernel.spi.flow.model.FlowState;

final class RuntimeFlowContext implements FlowContext {

    private final FlowKey key;
    private final String definitionName;
    private final RuntimeFlowContextStateView stateView;

    /* default */ RuntimeFlowContext(FlowKey key,
                                     String definitionName,
                                     RuntimeFlowContextStateView stateView) {
        this.key = key;
        this.definitionName = definitionName;
        this.stateView = stateView;
    }

    @Override
    public long instanceIdMost() {
        return key.instanceIdMost();
    }

    @Override
    public long instanceIdLeast() {
        return key.instanceIdLeast();
    }

    @Override
    public String definitionName() {
        return definitionName;
    }

    @Override
    public int currentStep() {
        return stateView.currentStep();
    }

    @Override
    public FlowState state() {
        return stateView.state();
    }

    @Override
    public long timeoutNanos() {
        return stateView.timeoutNanos();
    }
}
