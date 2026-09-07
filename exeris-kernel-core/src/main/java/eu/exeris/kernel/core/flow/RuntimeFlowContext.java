/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.core.flow;

import eu.exeris.kernel.spi.flow.model.FlowContext;
import eu.exeris.kernel.spi.flow.model.FlowState;

/**
 * Core: the {@link FlowContext} view of a live or restored flow instance, reading straight
 * through to a {@link RuntimeFlowContextStateView} rather than holding any mutable state itself.
 *
 * <p>Built once per {@link RuntimeFlowInstance} and handed to that instance's step and
 * compensation actions and to lookup callers such as {@code FlowScheduler.lookupParked}. Because
 * every accessor but {@link #instanceIdMost()} and {@link #instanceIdLeast()} delegates to the
 * backing view, this object never falls out of sync with the instance it fronts.
 */
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
