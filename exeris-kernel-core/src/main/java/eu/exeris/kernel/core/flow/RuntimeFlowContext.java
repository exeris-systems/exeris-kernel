/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
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
