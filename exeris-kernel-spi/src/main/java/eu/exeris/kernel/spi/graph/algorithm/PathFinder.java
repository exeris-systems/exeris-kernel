/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.spi.graph.algorithm;

import eu.exeris.kernel.spi.graph.model.PathResult;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * SPI: Shortest-path algorithm contract.
 *
 * @implNote Community runs a heap-based Dijkstra over {@code ArrayList} and
 *           {@code PriorityQueue}, with arena-scoped {@code LoanedBuffer} temporaries for
 *           large adjacency sets. Enterprise keeps adjacency arrays and the priority queue
 *           off-heap in slab pools, with zero allocation after the graph is loaded.
 * @since 0.5
 */
public interface PathFinder {

    /**
     * Returns the shortest path between {@code source} and {@code target}, weighted by
     * {@code weightFn}.
     *
     * @param source   source node ID
     * @param target   target node ID
     * @param weightFn function to calculate edge weights
     * @return path result (may indicate not found)
     */
    PathResult findShortestPath(UUID source, UUID target, EdgeWeightFunction weightFn);

    /**
     * Returns the shortest path from {@code source} to each node in {@code targets},
     * weighted by {@code weightFn}.
     *
     * @param source   source node ID
     * @param targets  set of target node IDs
     * @param weightFn function to calculate edge weights
     * @return an unmodifiable {@link Map} of target to path result
     * @implSpec Implementations MUST return an unmodifiable map, to avoid a defensive copy
     *           on the hot path. Callers MUST NOT attempt to modify it or cache a mutable
     *           reference.
     */
    Map<UUID, PathResult> findShortestPaths(UUID source, Set<UUID> targets, EdgeWeightFunction weightFn);

    /**
     * Returns up to {@code maxPaths} shortest paths between {@code source} and
     * {@code target}, in increasing order of cost (Yen's algorithm).
     *
     * @param source   source node ID
     * @param target   target node ID
     * @param maxPaths number of paths to find
     * @param weightFn function to calculate edge weights
     * @return an unmodifiable {@link List} of up to {@code maxPaths} shortest paths
     * @implSpec Implementations MUST return an unmodifiable list, to avoid a defensive copy.
     *           Callers MUST NOT attempt to modify the returned list.
     */
    List<PathResult> findKShortestPaths(UUID source, UUID target, int maxPaths, EdgeWeightFunction weightFn);

    /**
     * Returns whether at least one path exists between {@code source} and {@code target}.
     *
     * @param source source node ID
     * @param target target node ID
     * @return {@code true} if at least one path exists
     */
    boolean pathExists(UUID source, UUID target);

    /**
     * Returns this algorithm's name for diagnostics.
     *
     * @return algorithm name (e.g. "dijkstra", "bfs", "a-star")
     */
    String algorithmName();
}


