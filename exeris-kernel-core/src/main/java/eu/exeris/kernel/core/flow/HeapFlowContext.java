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
 * @param instanceIdMost    high 64 bits of the flow instance UUID
 * @param instanceIdLeast   low 64 bits of the flow instance UUID
 * @param definitionName    the name of the {@code FlowDefinition} the instance is scheduled from
 * @param currentStep       the step index the instance starts at
 * @param state             the lifecycle state the instance starts in
 * @param timeoutNanos      the absolute timeout deadline, in the {@code System.nanoTime()} epoch
 * @since 0.5
 */
record HeapFlowContext(
        long instanceIdMost,
        long instanceIdLeast,
        String definitionName,
        int currentStep,
        FlowState state,
        long timeoutNanos
) implements FlowContext {}
