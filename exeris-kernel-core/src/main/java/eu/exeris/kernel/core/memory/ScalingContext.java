/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.core.memory;

import java.util.Objects;

/**
 * Core: Immutable SLA threshold configuration consumed by {@link ResourceArbiter}
 * to apply per-tier load-shedding policies.
 *
 * <h2>Purpose</h2>
 * <p>Different service tiers have different tolerance for memory pressure before
 * load-shedding kicks in. A {@code premium} tenant has wider headroom before rejection;
 * a {@code free} tenant is shed first when memory is tight.
 *
 * <p>The {@link ResourceArbiter} selects the appropriate {@code ScalingContext} based
 * on the active request's service tier (propagated via {@code ScopedValue}).
 *
 * <h2>Valhalla Readiness (JEP 401)</h2>
 * <p>All fields are primitives or immutable value types. No identity operations
 * ({@code ==}, {@code synchronized}, {@code System.identityHashCode()}) are used
 * on instances of this record.
 * Declared {@code value record} on the `preview` line (JEP 401); the distributed line compiles
 * the same source as an identity {@code record}, and the modifier is asserted by
 * {@code Class::isValue} in the module's value-carrier registry test.
 *
 * <h2>Predefined Profiles</h2>
 * <p>Use {@link #premium()}, {@link #standard()}, or {@link #free()} factory methods
 * for the three canonical SLA tiers. Custom thresholds can be constructed directly.
 *
 * @param tierName            human-readable tier identifier (e.g., {@code "premium"})
 * @param throttleUtilization utilization ratio at which {@link ResourceArbiter.Action#THROTTLE}
 *                            activates for this tier; range {@code [0.0, 1.0]}
 * @param rejectUtilization   utilization ratio at which {@link ResourceArbiter.Action#REJECT}
 *                            activates; must be {@code > throttleUtilization}
 * @param shedUtilization     utilization ratio at which {@link ResourceArbiter.Action#SHED_LOAD}
 *                            activates; must be {@code > rejectUtilization}
 * @param maxQueueDepth       maximum in-flight request queue depth before shedding
 *                            (maps to performance contract: 5 000 = saturated)
 * @param maxRttMs            maximum acceptable RTT in milliseconds for this tier before
 *                            the arbiter considers the context degraded
 *
 * @implNote INCUBATING — Full integration with {@link ResourceArbiter} planned for v0.5.0.
 *         Multi-tenant SLA shedding is currently at TRL-2. The {@code ResourceArbiter}
 *         presently uses hardcoded {@link WatermarkLevel} boundaries and does not yet
 *         consume {@code ScalingContext}. Do NOT use on production hot paths until
 *         {@code ResourceArbiter} integration is complete.
 * @see ResourceArbiter
 * @see <a href="../../../../docs/subsystems/memory.md">Memory Subsystem: ScalingContext</a>
 * @since 0.5.0
 */
public value record ScalingContext(
        String tierName,
        double throttleUtilization,
        double rejectUtilization,
        double shedUtilization,
        int maxQueueDepth,
        int maxRttMs
) {

    // =========================================================================
    // Canonical constructor — strict validation
    // =========================================================================

    /**
     * Compact canonical constructor with threshold ordering enforcement.
     */
    public ScalingContext {
        Objects.requireNonNull(tierName, "tierName must not be null");
        if (tierName.isBlank()) {
            throw new IllegalArgumentException("tierName must not be blank");
        }
        if (throttleUtilization < 0.0 || throttleUtilization >= 1.0) {
            throw new IllegalArgumentException(
                    "throttleUtilization must be in [0.0, 1.0), got: " + throttleUtilization);
        }
        if (rejectUtilization <= throttleUtilization || rejectUtilization >= 1.0) {
            throw new IllegalArgumentException(
                    "rejectUtilization must be in (" + throttleUtilization + ", 1.0), got: "
                            + rejectUtilization);
        }
        if (shedUtilization <= rejectUtilization || shedUtilization > 1.0) {
            throw new IllegalArgumentException(
                    "shedUtilization must be in (" + rejectUtilization + ", 1.0], got: "
                            + shedUtilization);
        }
        if (maxQueueDepth <= 0) {
            throw new IllegalArgumentException("maxQueueDepth must be > 0, got: " + maxQueueDepth);
        }
        if (maxRttMs <= 0) {
            throw new IllegalArgumentException("maxRttMs must be > 0, got: " + maxRttMs);
        }
    }

    // =========================================================================
    // Predefined SLA profiles
    // =========================================================================

    /**
     * Premium tier — widest headroom, shed last.
     *
     * <p>Thresholds: throttle at 75%, reject at 88%, shed at 97%.
     * Queue depth: 8 000. RTT limit: 500 ms.
     *
     * @return premium scaling context; never {@code null}
     */
    public static ScalingContext premium() {
        return new ScalingContext("premium", 0.75, 0.88, 0.97, 8_000, 500);
    }

    /**
     * Standard tier — balanced thresholds.
     *
     * <p>Thresholds: throttle at 70%, reject at 85%, shed at 95%.
     * Matches the default {@link WatermarkLevel} boundaries.
     * Queue depth: 5 000 (performance contract: saturated threshold).
     * RTT limit: 250 ms.
     *
     * @return standard scaling context; never {@code null}
     */
    public static ScalingContext standard() {
        return new ScalingContext("standard", 0.70, 0.85, 0.95, 5_000, 250);
    }

    /**
     * Free tier — shed first under pressure.
     *
     * <p>Thresholds: throttle at 60%, reject at 75%, shed at 85%.
     * Queue depth: 2 000 (performance contract: warning threshold).
     * RTT limit: 100 ms.
     *
     * @return free-tier scaling context; never {@code null}
     */
    public static ScalingContext free() {
        return new ScalingContext("free", 0.60, 0.75, 0.85, 2_000, 100);
    }

    // =========================================================================
    // Decision helpers — O(1) threshold checks for the hot path
    // =========================================================================

    /**
     * Returns the {@link ResourceArbiter.Action} appropriate for this tier given the
     * current memory utilization ratio.
     *
     * <p>O(1) — three double comparisons, no allocation.
     *
     * @param utilization current memory utilization in {@code [0.0, 1.0]}
     * @return the action to take; never {@code null}
     */
    public ResourceArbiter.Action actionFor(double utilization) {
        if (utilization >= shedUtilization) {
            return ResourceArbiter.Action.SHED_LOAD;
        }
        if (utilization >= rejectUtilization) {
            return ResourceArbiter.Action.REJECT;
        }
        if (utilization >= throttleUtilization) {
            return ResourceArbiter.Action.THROTTLE;
        }
        return ResourceArbiter.Action.ALLOW;
    }

    /**
     * Returns {@code true} if the given queue depth exceeds this tier's limit.
     *
     * @param currentQueueDepth current number of in-flight requests
     * @return {@code true} if shedding should be triggered due to queue saturation
     */
    public boolean isQueueSaturated(int currentQueueDepth) {
        return currentQueueDepth > maxQueueDepth;
    }
}
