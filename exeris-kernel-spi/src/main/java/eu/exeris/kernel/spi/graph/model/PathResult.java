/*
 * Copyright (C) 2025-2026 Exeris. All rights reserved.
 *
 * This code is part of the Exeris Systems.
 * Distributed under the proprietary Exeris Software License.
 * Unauthorized copying or distribution is prohibited.
 */
package eu.exeris.kernel.spi.graph.model;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Valhalla-Ready: Result of a shortest-path algorithm execution.
 *
 * <p>Will be migrated to {@code value record} once JEP 401 is mainline.
 * Avoid identity operations.
 *
 * @param source    source node ID
 * @param target    target node ID
 * @param path      ordered list of node IDs (source first, target last); empty if not found
 * @param totalCost total cost / distance of path
 * @param hopCount  number of edges in path
 * @param algorithm algorithm used (e.g. "dijkstra", "bfs")
 *
 * @since 0.5.0
 */
public record PathResult(
        UUID source,
        UUID target,
        List<UUID> path,
        double totalCost,
        int hopCount,
        String algorithm
) {
    /**
     * Compact constructor — validates required fields and performs defensive copy of path.
     *
     * <p>{@code source}, {@code target}, and {@code algorithm} are mandatory; passing
     * {@code null} throws {@link NullPointerException} immediately (fail-fast).
     * {@code path} may be {@code null} and is normalised to an empty list.
     */
    public PathResult {
        Objects.requireNonNull(source,    "source must not be null");
        Objects.requireNonNull(target,    "target must not be null");
        Objects.requireNonNull(algorithm, "algorithm must not be null");
        path = path != null ? List.copyOf(path) : List.of();
    }

    /**
     * Checks if a valid path was found.
     *
     * <p>Invariant: {@code !found()} is always equivalent to
     * {@code totalCost == Double.POSITIVE_INFINITY && path.isEmpty()}.
     * Callers SHOULD use this method rather than inspecting {@code totalCost}
     * directly to avoid coupling to the sentinel value.
     *
     * @return {@code true} if a path exists between {@code source} and {@code target}
     */
    public boolean found() {
        return !path.isEmpty();
    }

    /**
     * Gets average cost per hop.
     *
     * @return average cost (totalCost / hopCount), or 0.0 if no hops
     */
    public double averageCostPerHop() {
        return hopCount == 0 ? 0.0 : totalCost / hopCount;
    }

    /**
     * Checks if this is a direct connection (single hop).
     *
     * @return true if path has exactly 2 nodes (1 edge)
     */
    public boolean isDirectConnection() {
        return path.size() == 2;
    }

    /**
     * Factory for a "not found" result.
     *
     * <p><b>Sentinel invariants</b> — the returned record always satisfies:
     * <ul>
     *   <li>{@link #found()} returns {@code false}</li>
     *   <li>{@link #path()} is empty</li>
     *   <li>{@link #totalCost()} is {@link Double#POSITIVE_INFINITY}</li>
     *   <li>{@link #hopCount()} is {@code 0}</li>
     * </ul>
     *
     * <p><b>Design note (zero-allocation contract):</b> {@code Optional<PathResult>}
     * was deliberately rejected here. Wrapping every result in {@code Optional}
     * would introduce a heap allocation on every successful path query — incompatible
     * with the Enterprise tier's zero-alloc hot-path mandate. Callers MUST check
     * {@link #found()} before consuming {@link #path()} or {@link #totalCost()}.
     *
     * @param source    source node ID
     * @param target    target node ID
     * @param algorithm algorithm that was executed
     * @return a "not found" sentinel result; never {@code null}
     */
    public static PathResult notFound(UUID source, UUID target, String algorithm) {
        return new PathResult(source, target, List.of(), Double.POSITIVE_INFINITY, 0, algorithm);
    }
}

