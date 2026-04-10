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
