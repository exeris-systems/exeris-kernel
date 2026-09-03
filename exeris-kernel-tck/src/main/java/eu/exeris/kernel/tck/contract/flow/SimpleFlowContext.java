/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.tck.contract.flow;

import eu.exeris.kernel.spi.flow.model.FlowContext;
import eu.exeris.kernel.spi.flow.model.FlowState;

/**
 * Minimal heap-based {@link FlowContext} for TCK test fixtures.
 *
 * @since 0.5.0
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
