/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.spi.graph.model;

import java.util.Objects;
import java.util.UUID;

/**
 * Immutable request describing a graph traversal: where to start, along which edge type,
 * how deep to go, and what to include in the result.
 *
 * <h2>Valhalla Readiness</h2>
 * <p>Structured so it can be migrated to a {@code value record} once JEP 401 is mainline.
 * Avoid identity operations ({@code ==}, {@code synchronized}, {@code System.identityHashCode()}).
 *
 * @param startNodeId      UUID of the starting node
 * @param edgeDescriptor   the edge type to traverse
 * @param maxDepth         maximum traversal depth (≥ 1)
 * @param includeStartNode whether to include the start node in results
 * @param includePayload   whether to include node payload data in results
 *
 * @since 0.5
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
     * Rejects a {@code null} {@code startNodeId} or {@code edgeDescriptor}, and a
     * {@code maxDepth} below {@link #MIN_DEPTH}.
     *
     * @throws NullPointerException     if {@code startNodeId} or {@code edgeDescriptor} is
     *                                  {@code null}
     * @throws IllegalArgumentException if {@code maxDepth} is below {@link #MIN_DEPTH}
     */
    public GraphTraversal {
        Objects.requireNonNull(startNodeId, "startNodeId");
        Objects.requireNonNull(edgeDescriptor, "edgeDescriptor");
        if (maxDepth < MIN_DEPTH) {
            throw new IllegalArgumentException("maxDepth must be >= " + MIN_DEPTH + ", got: " + maxDepth);
        }
    }

    /**
     * Returns a traversal request that excludes the start node and its payload from the
     * result.
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


