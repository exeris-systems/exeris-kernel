/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.core.memory;

/**
 * Thin adapter between {@link ResourceArbiter} and {@link ResourceArbiterDecisionEvent}:
 * converts the {@link ResourceArbiter.Context} enum to the plain {@code String} name that
 * the event type stores.
 *
 * <p>Holds no fields and is never instantiated.
 */
/* default */ final class ResourceArbiterDecisionTelemetry {

    private ResourceArbiterDecisionTelemetry() {
    }

    /**
     * Converts {@code context} to its name and forwards to
     * {@link ResourceArbiterDecisionEvent#emit(ResourceArbiter.Action, String, int, long)}.
     *
     * @param action         the arbitration action taken
     * @param context        the arbitration context evaluated
     * @param utilizationPct memory utilization in percent {@code [0..100]}
     * @param decisionNs     nanosecond timestamp of the decision
     */
    /* default */ static void emit(ResourceArbiter.Action action,
                                   ResourceArbiter.Context context,
                                   int utilizationPct,
                                   long decisionNs) {
        ResourceArbiterDecisionEvent.emit(action, context.contextName(), utilizationPct, decisionNs);
    }
}