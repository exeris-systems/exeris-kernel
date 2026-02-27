/*
 * Copyright (C) 2025-2026 Exeris. All rights reserved.
 *
 * This code is part of the Exeris Systems.
 * Distributed under the proprietary Exeris Software License.
 * Unauthorized copying or distribution is prohibited.
 */
package eu.exeris.kernel.spi.flow;

/**
 * Immutable descriptor of the capabilities of a specific {@link FlowEngine} implementation.
 *
 * <h2>Usage</h2>
 * <p>Used by {@code KernelBootstrap} to populate JFR bootstrap events and emit operator
 * warnings (e.g., when deterministic execution is unavailable in Community tier).
 * TCK tests gate zero-alloc assertions against this descriptor.
 *
 * <h2>Valhalla Readiness</h2>
 * <p>All fields are {@code boolean} primitives — ideal for future {@code value record}
 * scalarisation. No identity operations used.
 *
 * @param deterministicExecution {@code true} if the engine guarantees deterministic step
 *                               ordering (Enterprise only)
 * @param offHeapDescriptors     {@code true} if step and transition descriptors are stored
 *                               off-heap in slab pools (Enterprise only)
 * @param lockFreeScheduler      {@code true} if the scheduler uses lock-free ring buffer
 *                               with {@code @Contended} head/tail padding (Enterprise only)
 * @param zeroGcAfterStart       {@code true} if zero heap allocations occur after
 *                               {@link FlowEngine#start()} (Enterprise only)
 * @param persistenceBacked      {@code true} if flow snapshot persistence is available
 * @param compensationSupport    {@code true} if backward compensation (saga rollback) is supported
 *
 * @since 0.5.0
 */
public record FlowEngineCapabilities(
        boolean deterministicExecution,
        boolean offHeapDescriptors,
        boolean lockFreeScheduler,
        boolean zeroGcAfterStart,
        boolean persistenceBacked,
        boolean compensationSupport
) {

    /**
     * Pre-built Community capabilities constant.
     * Use as the return value of {@link FlowEngine#capabilities()} in Community implementations.
     */
    public static final FlowEngineCapabilities COMMUNITY = new FlowEngineCapabilities(
            false, false, false, false, true, true
    );

    /**
     * Pre-built Enterprise capabilities constant.
     * Use as the return value of {@link FlowEngine#capabilities()} in Enterprise implementations.
     */
    public static final FlowEngineCapabilities ENTERPRISE = new FlowEngineCapabilities(
            true, true, true, true, true, true
    );
}

