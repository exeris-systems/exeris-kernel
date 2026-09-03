/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.core.flow;

import eu.exeris.kernel.spi.flow.model.FlowContext;
import eu.exeris.kernel.spi.flow.model.FlowState;

/**
 * Minimal heap-backed {@link FlowContext} used by {@link FlowChoreographyBridge}
 * when scheduling new flow instances from choreography events.
 *
 * @since 0.5.0
 */
record HeapFlowContext(
        long instanceIdMost,
        long instanceIdLeast,
        String definitionName,
        int currentStep,
        FlowState state,
        long timeoutNanos
) implements FlowContext {}
