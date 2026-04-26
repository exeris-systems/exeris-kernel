/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
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