/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.spi.graph.model;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Immutable request for a heterogeneous multi-hop traversal: a start node and an ordered list of hops,
 * each with its own repetition range. There is no global depth: the path length lies in
 * [Σ minRepeat, Σ maxRepeat]. {@code GraphTraversal(start, edge, d, …)} is exactly
 * {@code new GraphPathSpec(start, List.of(new GraphHop(edge, 1, d)))}.
 *
 * <h2>Valhalla Readiness</h2>
 * <p>Structured so it can be migrated to a {@code value record} once JEP 401 is mainline.
 * Avoid identity operations ({@code ==}, {@code synchronized}, {@code System.identityHashCode()}).
 *
 * @param startNodeId node the first hop starts from
 * @param hops        ordered hops; copied immutably, never empty
 * @since 0.13
 */
public record GraphPathSpec(UUID startNodeId, List<GraphHop> hops) {

    /**
     * Rejects {@code null} start node or hops, and empty hops.
     *
     * @throws NullPointerException     if {@code startNodeId}, {@code hops} or any hop is {@code null}
     * @throws IllegalArgumentException if {@code hops} is empty
     */
    public GraphPathSpec {
        Objects.requireNonNull(startNodeId, "startNodeId must not be null");
        Objects.requireNonNull(hops, "hops must not be null");
        hops = List.copyOf(hops);
        if (hops.isEmpty()) {
            throw new IllegalArgumentException("hops must not be empty");
        }
    }
}
