/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.core.graph;

import eu.exeris.kernel.spi.exceptions.graph.PathNotFoundException;
import eu.exeris.kernel.spi.graph.algorithm.EdgeWeightFunction;
import eu.exeris.kernel.spi.graph.algorithm.PathFinder;
import eu.exeris.kernel.spi.graph.model.PathResult;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Core: Algorithm orchestrator — delegates Dijkstra, BFS, and K-shortest traversals
 * to pluggable {@link PathFinder} implementations.
 *
 * <h2>Intent over Implementation (graph.md §Core Philosophy)</h2>
 * <p>The orchestrator is the single decision point between a caller's high-level
 * "find shortest path" intent and the driver-specific algorithm. Community drivers
 * supply a heap-based Dijkstra; Enterprise drivers supply an off-heap, zero-alloc
 * variant backed by slab-pinned adjacency arrays. The Core knows <em>nothing</em>
 * about that difference — it delegates uniformly through the {@link PathFinder} SPI.
 *
 * <h2>JFR-First</h2>
 * <p>Every algorithm invocation emits an {@link AlgoOrchestratorEvent} carrying the
 * algorithm name, source/target hop count, and total cost. The event is written even
 * when no path is found (cost = {@code +∞}).
 *
 * <h2>Error Strategy (Hot-Path Contract)</h2>
 * <p>When {@link PathResult#found()} is {@code false} and the caller requests strict
 * failure semantics, the orchestrator throws the pre-allocated sentinel
 * {@link PathNotFoundException} (EX-GRPH-5004). For bulk operations, callers receive
 * unmodifiable {@link Map}s and can filter by {@link PathResult#found()} without
 * exception overhead.
 *
 * <h2>Thread Safety</h2>
 * <p>Stateless beyond the injected {@link PathFinder}. Safe to share across all
 * Virtual Threads if the underlying {@link PathFinder} is thread-safe (Community:
 * yes; Enterprise: yes via carrier-affine slab pools).
 *
 * @since 0.5.0
 */
public final class AlgoOrchestrator {

    private static final String NULL_SOURCE   = "source must not be null";
    private static final String NULL_TARGET   = "target must not be null";
    private static final String NULL_WEIGHT   = "weightFn must not be null";
    private static final String NULL_TARGETS  = "targets must not be null";
    private static final int    MIN_K_PATHS   = 1;

    private final PathFinder pathFinder;

    /**
     * Constructs the orchestrator with the given path finder.
     *
     * @param pathFinder pluggable path-finding algorithm (must not be {@code null})
     */
    public AlgoOrchestrator(PathFinder pathFinder) {
        this.pathFinder = Objects.requireNonNull(pathFinder, "pathFinder must not be null");
    }

    /**
     * Finds the shortest path between two nodes using the configured algorithm.
     *
     * <p>Uses {@link EdgeWeightFunction#DEFAULT} (stored weight as-is).
     *
     * @param source source node ID
     * @param target target node ID
     * @return path result — check {@link PathResult#found()} before consuming
     * @throws PathNotFoundException (EX-GRPH-5004) if no path exists and strict
     *                               semantics are enforced by the caller (use the
     *                               overload with {@code failIfNotFound} flag)
     */
    public PathResult findShortestPath(UUID source, UUID target) {
        return findShortestPath(source, target, EdgeWeightFunction.DEFAULT, false);
    }

    /**
     * Finds the shortest path between two nodes with explicit weight function.
     *
     * @param source          source node ID
     * @param target          target node ID
     * @param weightFn        weight function for edge cost calculation
     * @param failIfNotFound  if {@code true}, throws {@link PathNotFoundException} when
     *                        no path is found; if {@code false}, returns a
     *                        {@link PathResult#notFound(UUID, UUID, String)} sentinel
     * @return path result
     * @throws PathNotFoundException (EX-GRPH-5004) when {@code failIfNotFound} is {@code true}
     *                               and no path is found
     */
    public PathResult findShortestPath(UUID source, UUID target,
                                       EdgeWeightFunction weightFn, boolean failIfNotFound) {
        Objects.requireNonNull(source,   NULL_SOURCE);
        Objects.requireNonNull(target,   NULL_TARGET);
        Objects.requireNonNull(weightFn, NULL_WEIGHT);

        AlgoOrchestratorEvent event = new AlgoOrchestratorEvent();
        event.begin();
        event.algorithmName = pathFinder.algorithmName();
        event.operationType = "SHORTEST_PATH";

        PathResult result = pathFinder.findShortestPath(source, target, weightFn);

        event.found     = result.found();
        event.hopCount  = result.hopCount();
        event.totalCost = result.totalCost();
        event.commit();

        if (failIfNotFound && !result.found()) {
            throw new PathNotFoundException(source, target);
        }
        return result;
    }

    /**
     * Finds the shortest paths from one source to multiple targets.
     *
     * <p>Returns an unmodifiable map — callers MUST NOT attempt to modify or retain
     * a mutable reference. Results for unreachable targets have {@link PathResult#found()}
     * returning {@code false}.
     *
     * @param source   source node ID
     * @param targets  set of target node IDs
     * @param weightFn weight function
     * @return unmodifiable map of target UUID → PathResult
     */
    public Map<UUID, PathResult> findShortestPaths(UUID source, Set<UUID> targets,
                                                   EdgeWeightFunction weightFn) {
        Objects.requireNonNull(source,   NULL_SOURCE);
        Objects.requireNonNull(targets,  NULL_TARGETS);
        Objects.requireNonNull(weightFn, NULL_WEIGHT);

        AlgoOrchestratorEvent event = new AlgoOrchestratorEvent();
        event.begin();
        event.algorithmName = pathFinder.algorithmName();
        event.operationType = "MULTI_TARGET_SHORTEST_PATH";

        Map<UUID, PathResult> results = pathFinder.findShortestPaths(source, targets, weightFn);

        long foundCount = results.values().stream().filter(PathResult::found).count();
        event.found     = foundCount > 0;
        event.hopCount  = (int) foundCount;
        event.totalCost = Double.NaN;
        event.commit();

        return results;
    }

    /**
     * Finds K shortest paths between two nodes (Yen's algorithm).
     *
     * <p>Returns an unmodifiable list of up to {@code maxPaths} results ordered by
     * ascending total cost. The list may be shorter than {@code maxPaths} if fewer
     * paths exist.
     *
     * @param source   source node ID
     * @param target   target node ID
     * @param maxPaths maximum number of paths to return (≥ 1)
     * @param weightFn weight function
     * @return unmodifiable list of path results, ordered by cost ascending
     */
    public List<PathResult> findKShortestPaths(UUID source, UUID target,
                                               int maxPaths, EdgeWeightFunction weightFn) {
        Objects.requireNonNull(source,   NULL_SOURCE);
        Objects.requireNonNull(target,   NULL_TARGET);
        Objects.requireNonNull(weightFn, NULL_WEIGHT);
        if (maxPaths < MIN_K_PATHS) {
            throw new IllegalArgumentException("maxPaths must be >= 1, got: " + maxPaths);
        }

        AlgoOrchestratorEvent event = new AlgoOrchestratorEvent();
        event.begin();
        event.algorithmName = pathFinder.algorithmName();
        event.operationType = "K_SHORTEST_PATHS";

        List<PathResult> results = pathFinder.findKShortestPaths(source, target, maxPaths, weightFn);

        event.found     = !results.isEmpty();
        event.hopCount  = results.size();
        event.totalCost = results.isEmpty() ? Double.POSITIVE_INFINITY : results.getFirst().totalCost();
        event.commit();

        return results;
    }

    /**
     * Quick reachability check — no path data returned, O(1) short-circuit possible
     * in driver implementations.
     *
     * @param source source node ID
     * @param target target node ID
     * @return {@code true} if at least one path exists
     */
    public boolean pathExists(UUID source, UUID target) {
        Objects.requireNonNull(source, NULL_SOURCE);
        Objects.requireNonNull(target, NULL_TARGET);
        return pathFinder.pathExists(source, target);
    }

    /**
     * Returns the algorithm name of the active path finder, for diagnostics.
     *
     * @return algorithm name (e.g. "dijkstra", "bfs", "a-star")
     */
    public String algorithmName() {
        return pathFinder.algorithmName();
    }
}

