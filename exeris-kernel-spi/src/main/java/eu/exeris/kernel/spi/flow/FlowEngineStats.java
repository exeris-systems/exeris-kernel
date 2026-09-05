/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.spi.flow;

/**
 * Point-in-time snapshot of {@link FlowEngine} runtime statistics.
 *
 * <h2>Usage</h2>
 * <p>Returned by {@link FlowEngine#stats()} for diagnostic and JFR emission purposes.
 * MUST NOT be called from the hot dispatch loop — treat as an O(1) read of pre-computed
 * atomic counters.
 *
 * <h2>Valhalla Readiness</h2>
 * <p>All fields are primitives. No identity operations used. Ready for {@code value record}.
 *
 * @param activeFlows         current number of concurrently executing flow instances
 * @param parkedFlows         current number of suspended (parked) flow instances
 * @param completedFlows      total number of successfully completed flows since {@link FlowEngine#start()}
 * @param failedFlows         total number of flows that reached the
 *                            {@link eu.exeris.kernel.spi.flow.model.FlowState#FAILED_ROLLEDBACK}
 *                            terminal state (compensation completed after step failure)
 * @param compensationsRun    total number of backward compensation steps executed
 * @param stepExecutions      total number of individual step executions
 * @param schedulerQueueDepth current depth of the scheduler queue (Enterprise: ring buffer fill level)
 * @param slabUtilizationPct  percentage of off-heap slab pool in use (Enterprise); -1 for Community
 *
 * @since 0.5
 */
public record FlowEngineStats(
        long activeFlows,
        long parkedFlows,
        long completedFlows,
        long failedFlows,
        long compensationsRun,
        long stepExecutions,
        int  schedulerQueueDepth,
        int  slabUtilizationPct
) {

    // CHECKSTYLE.OFF: DeclarationOrder — static constants in records must follow components list

    /**
     * Zero-value stats snapshot — returned before engine start or in test contexts.
     *
     * <p>Cached constant following the {@code EventEngineStats.ZERO} convention.
     * {@link #empty()} returns this instance to avoid allocating a new record on every
     * monitoring poll — consistent with the "no object churn" Valhalla-readiness principle.
     */
    public static final FlowEngineStats ZERO = new FlowEngineStats(0L, 0L, 0L, 0L, 0L, 0L, 0, -1);

    // CHECKSTYLE.ON: DeclarationOrder

    /** Returns a zero-value stats snapshot (useful as a safe default before engine start). */
    public static FlowEngineStats empty() {
        return ZERO;
    }
}

