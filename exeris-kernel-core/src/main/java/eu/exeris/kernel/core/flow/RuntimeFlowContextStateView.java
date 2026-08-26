/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.core.flow;

import eu.exeris.kernel.spi.flow.model.FlowState;

/* default */ interface RuntimeFlowContextStateView {

    int currentStep();

    FlowState state();

    long timeoutNanos();
}