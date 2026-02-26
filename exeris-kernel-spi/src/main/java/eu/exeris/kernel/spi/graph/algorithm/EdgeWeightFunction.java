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

    /** Default weight function — uses stored weight as-is. */
    EdgeWeightFunction DEFAULT = (src, tgt, weight, props) -> weight;

    /** Uniform cost function — all edges cost 1.0. */
    EdgeWeightFunction UNIFORM = (src, tgt, weight, props) -> 1.0;

    /** Inverse weight function — prefer heavier edges. */
    EdgeWeightFunction INVERSE = (src, tgt, weight, props) -> 1.0 / Math.max(weight, 0.1);

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


