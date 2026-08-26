/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.core.memory;

/* default */ final class ResourceArbiterDecisionTelemetry {

    private ResourceArbiterDecisionTelemetry() {
    }

    /* default */ static void emit(ResourceArbiter.Action action,
                                   ResourceArbiter.Context context,
                                   int utilizationPct,
                                   long decisionNs) {
        ResourceArbiterDecisionEvent.emit(action, context.contextName(), utilizationPct, decisionNs);
    }
}