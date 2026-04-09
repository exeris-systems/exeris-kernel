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

    private final RuntimeFlowInstance instance;

    /* default */ RuntimeFlowContext(RuntimeFlowInstance instance) {
        this.instance = instance;
    }

    @Override
    public long instanceIdMost() {
        return instance.key().instanceIdMost();
    }

    @Override
    public long instanceIdLeast() {
        return instance.key().instanceIdLeast();
    }

    @Override
    public String definitionName() {
        return instance.definitionName();
    }

    @Override
    public int currentStep() {
        return instance.currentStep();
    }

    @Override
    public FlowState state() {
        return instance.state();
    }

    @Override
    public long timeoutNanos() {
        return instance.timeoutNanos();
    }
}
