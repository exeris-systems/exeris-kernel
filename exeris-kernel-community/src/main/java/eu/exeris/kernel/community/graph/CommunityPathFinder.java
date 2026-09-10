/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.graph;

import eu.exeris.kernel.spi.graph.algorithm.EdgeWeightFunction;
import eu.exeris.kernel.spi.graph.algorithm.PathFinder;
import eu.exeris.kernel.spi.graph.model.PathResult;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Queue;
import java.util.Set;
import java.util.UUID;

/**
 * Community-tier {@link PathFinder}: heap-based Dijkstra + Yen's k-shortest paths.
 *
 * <p>Adjacency is provided at construction time via {@link Builder} and is immutable
 * after construction; safe for concurrent access without synchronisation.
 *
 * @since 0.5
 */
// CouplingBetweenObjects: Dijkstra + Yen's require HashMap/ArrayList/PriorityQueue/HashSet/Deque —
// inherent algorithm data structures, not dependency coupling.
@SuppressWarnings({"PMD.TooManyMethods", "PMD.CouplingBetweenObjects", "PMD.CyclomaticComplexity"})
final class CommunityPathFinder implements PathFinder {

    private final Map<UUID, List<Neighbor>> adjacency;

    /** Package-private Valhalla-ready neighbor carrier. */
    /* default */ record Neighbor(UUID target, double weight, String properties) {}

    /* package */ CommunityPathFinder(Map<UUID, List<Neighbor>> adjacency) {
        Map<UUID, List<Neighbor>> copy = new HashMap<>();
        for (Map.Entry<UUID, List<Neighbor>> entry : adjacency.entrySet()) {
            copy.put(entry.getKey(), List.copyOf(entry.getValue()));
        }
        this.adjacency = Collections.unmodifiableMap(copy);
    }

    /**
     * Creates a new, empty {@link Builder}.
     *
     * @return a new builder
     */
    /* default */ static Builder builder() {
        return new Builder();
    }

    // =========================================================================
    // Builder
    // =========================================================================

    /**
     * Accumulates adjacency for one {@link CommunityPathFinder}. Backend implementations of
     * {@link CommunityGraphBackend#loadAdjacency} fill a builder with the edges of one
     * relationship type before {@link #build()} produces the immutable path finder.
     */
    /* default */ static final class Builder {

        private final Map<UUID, List<Neighbor>> adjacency = new HashMap<>();

        /**
         * Adds a directed edge with no properties.
         *
         * @param from         source node ID
         * @param targetNodeId target node ID
         * @param weight       edge weight
         * @return this builder
         */
        /* default */ Builder addEdge(UUID from, UUID targetNodeId, double weight) {
            return addEdge(from, targetNodeId, weight, null);
        }

        /**
         * Adds a directed edge from {@code from} to {@code targetNodeId}, and ensures
         * {@code targetNodeId} has an (initially empty) adjacency entry of its own so it is
         * reachable as a search origin even before any outgoing edge is added for it.
         *
         * @param from         source node ID
         * @param targetNodeId target node ID
         * @param weight       edge weight
         * @param properties   JSON properties string; may be {@code null}
         * @return this builder
         */
        /* default */ Builder addEdge(UUID from, UUID targetNodeId, double weight, String properties) {
            adjacency.computeIfAbsent(from, k -> new ArrayList<>())
                     .add(new Neighbor(targetNodeId, weight, properties));
            adjacency.computeIfAbsent(targetNodeId, k -> new ArrayList<>());
            return this;
        }

        /**
         * Builds a {@link CommunityPathFinder} from the edges added so far. The returned
         * instance copies this builder's adjacency; further calls to {@link #addEdge} do not
         * affect it.
         *
         * @return a new, immutable path finder
         */
        /* default */ CommunityPathFinder build() {
            return new CommunityPathFinder(adjacency);
        }
    }

    // =========================================================================
    // PathFinder SPI
    // =========================================================================

    /**
     * {@inheritDoc}
     *
     * <p>Runs one Dijkstra search from {@code source} over the full adjacency this instance
     * was built with.
     */
    @Override
    public PathResult findShortestPath(UUID source, UUID target, EdgeWeightFunction weightFn) {
        PathEntry entry = dijkstra(source, target, weightFn, Set.of(), Set.of());
        if (entry == null) {
            return PathResult.notFound(source, target, algorithmName());
        }
        return toPathResult(source, target, entry);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Runs one independent {@link #findShortestPath} call per target — a separate
     * Dijkstra search from {@code source} for each entry in {@code targets}, not a single
     * shared traversal.
     */
    @Override
    public Map<UUID, PathResult> findShortestPaths(UUID source, Set<UUID> targets,
                                                    EdgeWeightFunction weightFn) {
        Map<UUID, PathResult> result = new HashMap<>();
        for (UUID target : targets) {
            result.put(target, findShortestPath(source, target, weightFn));
        }
        return Collections.unmodifiableMap(result);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Runs Yen's algorithm on top of the {@link #findShortestPath} Dijkstra search.
     * {@link PathResult#algorithm()} on each returned result still reports
     * {@link #algorithmName()} ({@code "dijkstra"}) — it is not overridden to reflect that
     * Yen's algorithm produced the k-path set.
     */
    @Override
    public List<PathResult> findKShortestPaths(UUID source, UUID target, int maxPaths,
                                                EdgeWeightFunction weightFn) {
        return yens(source, target, maxPaths, weightFn);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Answers with an unweighted breadth-first search over the adjacency, independent of
     * {@link #findShortestPath}'s Dijkstra search and of any {@link EdgeWeightFunction}.
     */
    @Override
    public boolean pathExists(UUID source, UUID target) {
        Deque<UUID> queue = new ArrayDeque<>();
        Set<UUID> visited = new HashSet<>();
        queue.add(source);
        visited.add(source);
        while (!queue.isEmpty()) {
            UUID currentNodeId = queue.poll();
            if (currentNodeId.equals(target)) {
                return true;
            }
            for (Neighbor neighbor : adjacency.getOrDefault(currentNodeId, List.of())) {
                if (visited.add(neighbor.target())) {
                    queue.add(neighbor.target());
                }
            }
        }
        return false;
    }

    /**
     * Returns {@code "dijkstra"} unconditionally — the value every {@link PathResult}
     * produced by this class carries, including the k-shortest-path results from
     * {@link #findKShortestPaths}, which Yen's algorithm computes on top of Dijkstra rather
     * than Dijkstra alone.
     *
     * @return {@code "dijkstra"}
     */
    @Override
    public String algorithmName() {
        return "dijkstra";
    }

    // =========================================================================
    // Private carriers
    // =========================================================================

    private record PathEntry(List<UUID> path, double cost) implements Comparable<PathEntry> {
        @Override
        public int compareTo(PathEntry other) {
            return Double.compare(this.cost, other.cost);
        }
    }

    private record Item(UUID node, double distance) implements Comparable<Item> {
        @Override
        public int compareTo(Item other) {
            return Double.compare(this.distance, other.distance);
        }
    }

    // =========================================================================
    // Algorithm implementations
    // =========================================================================

    /**
     * Core Dijkstra helper. Supports edge/node exclusion for Yen's k-shortest paths.
     *
     * @return PathEntry or {@code null} if target is unreachable
     */
    private PathEntry dijkstra(UUID source, UUID target, EdgeWeightFunction weightFn,
                                Set<String> removedEdges, Set<UUID> removedNodes) {
        Map<UUID, Double> dist = new HashMap<>();
        Map<UUID, UUID> parent = new HashMap<>();
        Queue<Item> frontier = new PriorityQueue<>();

        for (UUID node : adjacency.keySet()) {
            if (!removedNodes.contains(node)) {
                dist.put(node, Double.POSITIVE_INFINITY);
            }
        }
        dist.put(source, 0.0);
        frontier.offer(new Item(source, 0.0));

        while (!frontier.isEmpty()) {
            Item current = frontier.poll();
            UUID currentNodeId = current.node();
            double currentDistance = current.distance();
            Double distU = dist.get(currentNodeId);
            if (distU != null && currentDistance <= distU && !currentNodeId.equals(target)) {
                relax(currentNodeId, weightFn, removedEdges, removedNodes, dist, parent, frontier);
            }
        }

        Double targetDist = dist.get(target);
        if (targetDist == null || !Double.isFinite(targetDist)) {
            return null;
        }

        List<UUID> path = new ArrayList<>();
        UUID current = target;
        while (current != null) {
            path.add(0, current);
            current = parent.get(current);
        }
        return new PathEntry(path, targetDist);
    }

    /** Yen's k-shortest paths using the Dijkstra helper above. */
    @SuppressWarnings("PMD.AvoidInstantiatingObjectsInLoops")
    private List<PathResult> yens(UUID source, UUID target, int maxPaths,
                                   EdgeWeightFunction weightFn) {
        List<PathEntry> kPaths = new ArrayList<>();
        Queue<PathEntry> candidates = new PriorityQueue<>();

        PathEntry first = dijkstra(source, target, weightFn, Set.of(), Set.of());
        if (first == null) {
            return List.of();
        }
        kPaths.add(first);

        for (int k = 1; k < maxPaths; k++) {
            List<UUID> prevNodes = kPaths.get(k - 1).path();

            for (int i = 0; i < prevNodes.size() - 1; i++) {
                UUID spurNode = prevNodes.get(i);
                List<UUID> rootPath = prevNodes.subList(0, i + 1);
                Set<String> removedEdges = collectRemovedEdges(kPaths, rootPath, i);

                Set<UUID> removedNodes = i > 0
                        ? new HashSet<>(rootPath.subList(0, i))
                        : Set.of();

                PathEntry spur = dijkstra(spurNode, target, weightFn, removedEdges, removedNodes);
                addSpurCandidate(i, rootPath, spur, weightFn, kPaths, candidates);
            }

            if (candidates.isEmpty()) {
                break;
            }
            kPaths.add(candidates.poll());
        }

        return kPaths.stream()
                .map(e -> toPathResult(source, target, e))
                .toList();
    }

    private boolean isExcluded(UUID from, Neighbor neighbor, Set<String> removedEdges, Set<UUID> removedNodes) {
        return removedEdges.contains(from + "->" + neighbor.target()) || removedNodes.contains(neighbor.target());
    }

    private void relax(UUID currentNodeId, EdgeWeightFunction weightFn,
                        Set<String> removedEdges, Set<UUID> removedNodes,
                        Map<UUID, Double> dist, Map<UUID, UUID> parent, Queue<Item> frontier) {
        double distU = dist.get(currentNodeId);
        for (Neighbor neighbor : adjacency.getOrDefault(currentNodeId, List.of())) {
            if (isExcluded(currentNodeId, neighbor, removedEdges, removedNodes)) {
                continue;
            }
            double resolvedWeight = weightFn.calculateWeight(
                    currentNodeId,
                    neighbor.target(),
                    neighbor.weight(),
                    neighbor.properties());
            double newDist = distU + resolvedWeight;
            if (newDist < dist.getOrDefault(neighbor.target(), Double.POSITIVE_INFINITY)) {
                dist.put(neighbor.target(), newDist);
                parent.put(neighbor.target(), currentNodeId);
                frontier.offer(new Item(neighbor.target(), newDist));
            }
        }
    }

    private void addSpurCandidate(int spurIdx, List<UUID> rootPath, PathEntry spur,
                                   EdgeWeightFunction weightFn, List<PathEntry> kPaths,
                                   Queue<PathEntry> candidates) {
        if (spur == null) {
            return;
        }
        List<UUID> fullPath = new ArrayList<>(rootPath.subList(0, spurIdx));
        fullPath.addAll(spur.path());
        double rootCost = computePathCost(rootPath, weightFn);
        PathEntry candidate = new PathEntry(fullPath, rootCost + spur.cost());
        if (!isKnownPath(kPaths, candidates, candidate.path())) {
            candidates.offer(candidate);
        }
    }

    private Set<String> collectRemovedEdges(List<PathEntry> kPaths, List<UUID> rootPath, int spurIdx) {
        Set<String> removed = new HashSet<>();
        for (PathEntry p : kPaths) {
            List<UUID> pathNodes = p.path();
            if (pathNodes.size() > spurIdx + 1 && pathNodes.subList(0, spurIdx + 1).equals(rootPath)) {
                removed.add(pathNodes.get(spurIdx) + "->" + pathNodes.get(spurIdx + 1));
            }
        }
        return removed;
    }

    private boolean isKnownPath(List<PathEntry> kPaths, Collection<PathEntry> candidates,
                                 List<UUID> path) {
        for (PathEntry p : kPaths) {
            if (p.path().equals(path)) {
                return true;
            }
        }
        for (PathEntry p : candidates) {
            if (p.path().equals(path)) {
                return true;
            }
        }
        return false;
    }

    private double computePathCost(List<UUID> path, EdgeWeightFunction weightFn) {
        double cost = 0.0;
        for (int i = 0; i < path.size() - 1; i++) {
            UUID from = path.get(i);
            UUID targetNodeId = path.get(i + 1);
            for (Neighbor neighbor : adjacency.getOrDefault(from, List.of())) {
                if (neighbor.target().equals(targetNodeId)) {
                    cost += weightFn.calculateWeight(
                            from,
                            targetNodeId,
                            neighbor.weight(),
                            neighbor.properties());
                    break;
                }
            }
        }
        return cost;
    }

    private PathResult toPathResult(UUID source, UUID target, PathEntry entry) {
        return new PathResult(source, target, entry.path(), entry.cost(), 0, algorithmName());
    }
}
