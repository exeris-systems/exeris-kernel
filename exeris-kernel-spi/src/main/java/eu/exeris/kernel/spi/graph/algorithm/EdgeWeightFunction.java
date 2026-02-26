/*
 * Copyright (C) 2025-2026 Exeris. All rights reserved.
 *
 * This code is part of the Exeris Systems.
 * Distributed under the proprietary Exeris Software License.
 * Unauthorized copying or distribution is prohibited.
 */
package eu.exeris.kernel.spi.graph.algorithm;

import java.util.UUID;

/**
 * Valhalla-Ready: Function to calculate weight for a graph edge.
 *
 * <p>Avoid identity operations. Will migrate to {@code value} interface
 * when JEP 401 is mainline.
 *
 * @since 0.5.0
 */
@FunctionalInterface
public interface EdgeWeightFunction {

    /**
     * Minimum weight floor used by {@link #INVERSE} to prevent division by zero.
     *
     * <p>Chosen as {@code 0.1} to keep inverse costs bounded below {@code 10.0}
     * for near-zero-weight edges, while still allowing meaningful differentiation
     * between edges in the {@code [0.1, ∞)} range. Edges with a stored weight
     * below this threshold are treated as having weight {@code 0.1} — they are
     * reachable but expensive under an inverse-cost model.
     *
     * <p>If your domain requires a different floor (e.g., {@code 0.01} for
     * high-precision cost graphs), provide a custom {@link EdgeWeightFunction}
     * lambda rather than using this built-in constant.
     */
    double INVERSE_MIN_WEIGHT = 0.1;

    /** Default weight function — uses stored weight as-is. */
    EdgeWeightFunction DEFAULT = (src, tgt, weight, props) -> weight;

    /** Uniform cost function — all edges cost 1.0. */
    EdgeWeightFunction UNIFORM = (src, tgt, weight, props) -> 1.0;

    /**
     * Inverse weight function — prefer heavier edges (lower cost = higher weight).
     *
     * <p>Uses {@link #INVERSE_MIN_WEIGHT} as the divisor floor to prevent
     * division by zero. Edges with {@code weight == 0} are treated as having
     * weight {@code 0.1}, yielding a cost of {@code 10.0} rather than infinity.
     * If zero-weight edges should be treated as unreachable, supply a custom
     * function that returns {@link Double#POSITIVE_INFINITY} for {@code weight == 0}.
     */
    EdgeWeightFunction INVERSE = (src, tgt, weight, props) ->
            1.0 / Math.max(weight, INVERSE_MIN_WEIGHT);

    /**
     * Calculates weight for an edge.
     *
     * @param source     source node ID
     * @param target     target node ID
     * @param weight     edge weight from storage
     * @param properties edge properties (JSON string, may be {@code null})
     * @return computed weight
     */
    double calculateWeight(UUID source, UUID target, double weight, String properties);
}


