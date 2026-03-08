/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
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
 * <h2>Community vs Enterprise</h2>
 * <ul>
 *   <li><b>Community:</b> Heap-based Dijkstra using {@code ArrayList} +
 *       {@code PriorityQueue}. Arena-scoped temporary buffers via
 *       {@code LoanedBuffer} for large adjacency sets.</li>
 *   <li><b>Enterprise:</b> Off-heap adjacency arrays stored in slab pools.
 *       Priority queue backed by raw pointer arrays. Zero allocation after
 *       graph load.</li>
 * </ul>
 *
 * @since 0.5.0
 */
public interface PathFinder {

    /**
     * Finds the shortest path between source and target node.
     *
     * @param source   source node ID
     * @param target   target node ID
     * @param weightFn function to calculate edge weights
     * @return path result (may indicate not found)
     */
    PathResult findShortestPath(UUID source, UUID target, EdgeWeightFunction weightFn);

    /**
     * Finds shortest paths from source to multiple targets.
     *
     * @param source   source node ID
     * @param targets  set of target node IDs
     * @param weightFn function to calculate edge weights
     * @return an <strong>unmodifiable</strong> {@link Map} of target to path result
     *         (zero-allocation view — callers MUST NOT attempt to modify or cache
     *         a mutable reference; implementations MUST return an unmodifiable map
     *         to avoid defensive copies on the hot-path)
     */
    Map<UUID, PathResult> findShortestPaths(UUID source, Set<UUID> targets, EdgeWeightFunction weightFn);

    /**
     * Finds k shortest paths (Yen's algorithm).
     *
     * @param source   source node ID
     * @param target   target node ID
     * @param maxPaths number of paths to find
     * @param weightFn function to calculate edge weights
     * @return an <strong>unmodifiable</strong> {@link List} of up to {@code maxPaths} shortest paths
     *         (zero-allocation view — callers MUST NOT attempt to modify the returned list;
     *         implementations MUST return an unmodifiable list to avoid defensive copies)
     */
    List<PathResult> findKShortestPaths(UUID source, UUID target, int maxPaths, EdgeWeightFunction weightFn);

    /**
     * Checks if any path exists between two nodes.
     *
     * @param source source node ID
     * @param target target node ID
     * @return true if at least one path exists
     */
    boolean pathExists(UUID source, UUID target);

    /**
     * Returns algorithm name for diagnostics.
     *
     * @return algorithm name (e.g. "dijkstra", "bfs", "a-star")
     */
    String algorithmName();
}


