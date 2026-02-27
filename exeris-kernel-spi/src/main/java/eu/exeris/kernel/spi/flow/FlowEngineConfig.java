/*
 * Copyright (C) 2025-2026 Exeris. All rights reserved.
 *
 * This code is part of the Exeris Systems.
 * Distributed under the proprietary Exeris Software License.
 * Unauthorized copying or distribution is prohibited.
 */
package eu.exeris.kernel.spi.flow;

import java.util.Objects;

/**
 * Immutable configuration for the {@link FlowEngine}.
 *
 * <h2>Design</h2>
 * <p>All configuration is expressed via primitives and {@link String} keys —
 * no references to implementation-specific classes. Both Community and Enterprise
 * implementations read only the fields they need; unknown fields are ignored.
 *
 * <h2>Valhalla Readiness</h2>
 * <p>Purely primitive fields with {@code String} keys limited to bootstrap path.
 * No identity operations ({@code ==}, {@code synchronized}, {@code identityHashCode}).
 * Ready for {@code value record} when JEP 401 is stable.
 *
 * @param engineName             human-readable engine name (for JFR / logging)
 * @param maxConcurrentFlows     maximum number of simultaneously active flow instances
 * @param timeoutNanos           default flow execution timeout in nanoseconds
 * @param maxSteps               maximum number of distinct step types (Enterprise: slab sizing)
 * @param maxTransitions         maximum number of registered transitions (Enterprise: slab sizing)
 * @param maxExecutionPlans      maximum number of compiled execution plans (Enterprise: slab sizing)
 * @param schedulerQueueCapacity ring buffer capacity for the flow scheduler (power-of-2 for Enterprise)
 * @param partitionName          memory partition name for Enterprise off-heap allocation
 * @param partitionBytes         total bytes to claim for the flow memory partition (Enterprise)
 * @param persistenceEnabled     whether flow snapshot persistence via SPI is enabled
 * @param compensationEnabled    whether backward compensation (saga rollback) is enabled
 *
 * @since 0.5.0
 */
public record FlowEngineConfig(
        String  engineName,
        int     maxConcurrentFlows,
        long    timeoutNanos,
        int     maxSteps,
        int     maxTransitions,
        int     maxExecutionPlans,
        int     schedulerQueueCapacity,
        String  partitionName,
        long    partitionBytes,
        boolean persistenceEnabled,
        boolean compensationEnabled
) {

    /** Compact constructor — validates invariants eagerly (fail-fast bootstrap). */
    public FlowEngineConfig {
        Objects.requireNonNull(engineName, "engineName must not be null");
        if (engineName.isBlank()) {
            throw new IllegalArgumentException("engineName must not be blank");
        }
        if (maxConcurrentFlows <= 0) {
            throw new IllegalArgumentException("maxConcurrentFlows must be > 0, got: " + maxConcurrentFlows);
        }
        if (timeoutNanos <= 0) {
            throw new IllegalArgumentException("timeoutNanos must be > 0, got: " + timeoutNanos);
        }
        if (maxSteps <= 0) {
            throw new IllegalArgumentException("maxSteps must be > 0, got: " + maxSteps);
        }
        if (maxTransitions < 0) {
            throw new IllegalArgumentException("maxTransitions must be >= 0, got: " + maxTransitions);
        }
        if (maxExecutionPlans <= 0) {
            throw new IllegalArgumentException("maxExecutionPlans must be > 0, got: " + maxExecutionPlans);
        }
        if (schedulerQueueCapacity <= 0) {
            throw new IllegalArgumentException("schedulerQueueCapacity must be > 0, got: " + schedulerQueueCapacity);
        }
    }

    /**
     * Sensible defaults suitable for Community (heap-based) usage.
     * Enterprise operators should override {@code partitionBytes} and slab-sizing fields.
     */
    public static FlowEngineConfig defaults(String engineName) {
        return new FlowEngineConfig(
                engineName,
                10_000,
                300_000_000_000L,   // 5 minutes in nanoseconds
                256,
                4_096,
                1_024,
                65_536,
                "flow",
                32L * 1024 * 1024,  // 32 MB
                true,
                true
        );
    }
}

