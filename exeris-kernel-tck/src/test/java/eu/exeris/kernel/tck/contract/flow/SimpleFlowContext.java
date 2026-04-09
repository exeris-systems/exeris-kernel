/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.tck.contract.flow;

import eu.exeris.kernel.spi.flow.model.FlowContext;
import eu.exeris.kernel.spi.flow.model.FlowState;

/**
 * Minimal heap-based {@link FlowContext} for TCK test fixtures.
 *
 * @since 0.6.0
 */
record SimpleFlowContext(
        long instanceIdMost,
        long instanceIdLeast,
        String definitionName
) implements FlowContext {

    @Override
    public int currentStep() {
        return 0;
    }

    @Override
    public FlowState state() {
        return FlowState.CREATED;
    }

    @Override
    public long timeoutNanos() {
        return Long.MAX_VALUE;
    }
}
