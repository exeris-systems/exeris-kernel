/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.core.memory;

/**
 * Core: Discrete memory pressure levels used by {@link WatermarkManager} and
 * {@link ResourceArbiter} to make "Stop/Go" load-shedding decisions.
 *
 * <h2>Level Semantics</h2>
 * <pre>
 *   NORMAL   ─ utilization &lt; WARNING threshold (default: 70%)
 *              Action: ALLOW all traffic.
 *
 *   WARNING  ─ utilization >= 70%, &lt; CRITICAL threshold (default: 85%)
 *              Action: THROTTLE non-critical work.
 *
 *   CRITICAL ─ utilization >= 85%, &lt; SHEDDING threshold (default: 95%)
 *              Action: REJECT new non-essential requests; preserve in-flight work.
 *
 *   SHEDDING ─ utilization >= 95%
 *              Action: SHED_LOAD — reply H3_EXCESSIVE_LOAD to all new connections.
 * </pre>
 *
 * <h2>Valhalla Readiness (JEP 401)</h2>
 * <p>This enum is a JVM singleton per constant — zero heap allocation on comparison,
 * no identity overhead. The threshold fields are {@code final double} primitives,
 * read directly by the branch predicates on the hot decision path with no boxing
 * or indirection.
 *
 * <h2>Performance Contract</h2>
 * <p>All threshold comparisons are O(1) double comparisons — no string parsing,
 * no map lookups, no allocation. This is safe for the hot ResourceArbiter path.
 *
 * @since 0.5
 * @see WatermarkManager
 * @see ResourceArbiter
 */
public enum WatermarkLevel {

    /**
     * Utilization below warning threshold — full capacity, traffic allowed.
     */
    NORMAL(0.0, 0.70),

    /**
     * Utilization between warning and critical — throttle non-critical work.
     */
    WARNING(0.70, 0.85),

    /**
     * Utilization between critical and shedding — reject non-essential requests.
     */
    CRITICAL(0.85, 0.95),

    /**
     * Utilization at or above shedding threshold — shed all new load (H3_EXCESSIVE_LOAD).
     */
    SHEDDING(0.95, Double.POSITIVE_INFINITY);

    /**
     * Inclusive lower bound of this level's utilization range.
     */
    private final double lowerInclusive;
    /**
     * Exclusive upper bound of this level's utilization range.
     */
    private final double upperExclusive;

    WatermarkLevel(double lowerInclusive, double upperExclusive) {
        this.lowerInclusive = lowerInclusive;
        this.upperExclusive = upperExclusive;
    }

    /**
     * Returns the inclusive lower utilization bound for this level.
     *
     * @return lower bound in [0.0, 1.0]
     */
    public double lowerBound() {
        return lowerInclusive;
    }

    /**
     * Returns the exclusive upper utilization bound for this level.
     *
     * @return the configured exclusive upper bound; {@link Double#POSITIVE_INFINITY} for {@link #SHEDDING}
     */
    public double upperBound() {
        return upperExclusive;
    }

    /**
     * Returns {@code true} if the given utilization ratio falls within this level's range.
     *
     * <p>This is an O(1) comparison, safe for the hot ResourceArbiter path.
     *
     * @param utilization utilization ratio in [0.0, 1.0]
     * @return {@code true} if {@code lowerInclusive <= utilization < upperExclusive}
     */
    public boolean contains(double utilization) {
        return utilization >= lowerInclusive && utilization < upperExclusive;
    }

    /**
     * Resolves the {@link WatermarkLevel} for a given utilization ratio.
     *
     * <p>O(1) — at most three double comparisons, against the {@code WARNING},
     * {@code CRITICAL} and {@code SHEDDING} lower bounds in turn; {@code NORMAL} is
     * the expected hot branch, matched by the first comparison.
     *
     * @param utilization ratio in [0.0, 1.0]; values > 1.0 map to {@code SHEDDING}
     * @return the matching watermark level; never {@code null}
     */
    public static WatermarkLevel forUtilization(double utilization) {
        if (utilization < WARNING.lowerInclusive) {
            return NORMAL;
        }
        if (utilization < CRITICAL.lowerInclusive) {
            return WARNING;
        }
        if (utilization < SHEDDING.lowerInclusive) {
            return CRITICAL;
        }
        return SHEDDING;
    }

    /**
     * Returns {@code true} if this level is more severe than {@code other}.
     *
     * @param other the level to compare against
     * @return {@code true} if this level's ordinal is greater than {@code other}'s
     */
    public boolean isMoreSevereThan(WatermarkLevel other) {
        return this.ordinal() > other.ordinal();
    }
}
