/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.core.flow;

import eu.exeris.kernel.spi.flow.model.FlowState;

/**
 * Core: the mutable, engine-owned state a {@link RuntimeFlowContext} reads to answer
 * {@link eu.exeris.kernel.spi.flow.model.FlowContext#currentStep()},
 * {@link eu.exeris.kernel.spi.flow.model.FlowContext#state()} and
 * {@link eu.exeris.kernel.spi.flow.model.FlowContext#timeoutNanos()}.
 *
 * <p>Implemented by {@link RuntimeFlowInstance} and kept as a narrow seam so
 * {@link RuntimeFlowContext} depends on these three accessors rather than the whole instance.
 */
/* default */ interface RuntimeFlowContextStateView {

    int currentStep();

    FlowState state();

    long timeoutNanos();
}