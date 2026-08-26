/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.spi.graph.model;

import java.util.Objects;
import java.util.UUID;

/**
 * Valhalla-Ready: Immutable graph traversal request.
 *
 * <p>Will be migrated to {@code value record} once JEP 401 is mainline.
 * Avoid identity operations.
 *
 * @param startNodeId      UUID of the starting node
 * @param edgeDescriptor   the edge type to traverse
 * @param maxDepth         maximum traversal depth (≥ 1)
 * @param includeStartNode whether to include the start node in results
 * @param includePayload   whether to include node payload data in results
 *
 * @since 0.5.0
 */
public record GraphTraversal(
        UUID startNodeId,
        GraphEdgeDescriptor edgeDescriptor,
        int maxDepth,
        boolean includeStartNode,
        boolean includePayload
) {

    /** Minimum allowed traversal depth. */
    private static final int MIN_DEPTH = 1;

    /**
     * Compact constructor with validation.
     */
    public GraphTraversal {
        Objects.requireNonNull(startNodeId, "startNodeId");
        Objects.requireNonNull(edgeDescriptor, "edgeDescriptor");
        if (maxDepth < MIN_DEPTH) {
            throw new IllegalArgumentException("maxDepth must be >= " + MIN_DEPTH + ", got: " + maxDepth);
        }
    }

    /**
     * Quick factory for simple traversals.
     *
     * @param startNodeId start node
     * @param edge        edge descriptor
     * @param maxDepth    max depth
     * @return traversal request
     */
    public static GraphTraversal create(UUID startNodeId, GraphEdgeDescriptor edge, int maxDepth) {
        return new GraphTraversal(startNodeId, edge, maxDepth, false, false);
    }
}


