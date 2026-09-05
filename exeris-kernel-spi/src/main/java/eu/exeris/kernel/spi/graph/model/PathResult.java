/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.spi.graph.model;

import eu.exeris.kernel.spi.util.SpiDiagnostics;

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
 * @since 0.5
 */
@SuppressWarnings("PMD.UnusedAssignment") // hopCount record component is normalised in the compact
// constructor (path.size()-1) — the caller-supplied value is intentionally discarded to enforce
// the path/hopCount invariant. Documented in the compact constructor Javadoc.
public record PathResult(
        UUID source,
        UUID target,
        List<UUID> path,
        double totalCost,
        int hopCount,
        String algorithm
) {
    /**
     * Compact constructor — validates required fields, performs defensive copy of path,
     * and normalises {@code hopCount} to be consistent with the actual path size.
     *
     * <p>{@code source}, {@code target}, and {@code algorithm} are mandatory; passing
     * {@code null} throws {@link NullPointerException} immediately (fail-fast).
     * {@code path} may be {@code null} and is normalised to an empty list.
     *
     * <p><b>hopCount normalisation:</b> the caller-supplied {@code hopCount} is overridden
     * by the formula {@code path.isEmpty() ? 0 : path.size() - 1}, making it impossible
     * to construct an inconsistent record (e.g., a 3-node path reporting 0 hops).
     * {@link #averageCostPerHop()} therefore always operates on a stable denominator.
     */
    public PathResult {
        Objects.requireNonNull(source,    "source must not be null");
        Objects.requireNonNull(target,    "target must not be null");
        Objects.requireNonNull(algorithm, "algorithm must not be null");
        path     = path != null ? List.copyOf(path) : List.of();
        // Derive hopCount from path — prevents caller from supplying an inconsistent value.
        hopCount = path.isEmpty() ? 0 : path.size() - 1;
    }

    /**
     * Checks if a valid path was found.
     *
     * <p>Returns {@code true} when both sentinel conditions hold:
     * <ul>
     *   <li>{@link #path()} is non-empty (an empty path always denotes "not found")</li>
     *   <li>{@link #totalCost()} is finite (not {@link Double#POSITIVE_INFINITY})</li>
     * </ul>
     * This includes valid zero-hop paths where {@code source == target} and the path
     * contains exactly one node — such paths are considered found with {@code hopCount == 0}.
     * Callers SHOULD use this method rather than inspecting fields directly to avoid
     * coupling to the sentinel representation.
     *
     * @return {@code true} if a path exists between {@code source} and {@code target}
     */
    public boolean found() {
        return !path.isEmpty() && Double.isFinite(totalCost);
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

    /**
     * Returns a diagnostic string with masked UUID values.
     *
     * <p>Full UUIDs are truncated via {@link SpiDiagnostics#maskUuid(UUID)} —
     * the canonical masking strategy shared across all SPI node-identity carriers
     * ({@code ImmutablePrincipal}, {@code PathResult}, etc.).
     * This prevents accidental exposure of node identifiers in logs, JFR events,
     * or exception messages.
     *
     * <p>Example: {@code PathResult[src=550e8400~***, tgt=6ba7b810~***, hops=3,
     * cost=12.5, found=true, algorithm=dijkstra]}
     */
    @Override
    public String toString() {
        return "PathResult[src=" + SpiDiagnostics.maskUuid(source)
                + ", tgt=" + SpiDiagnostics.maskUuid(target)
                + ", hops=" + hopCount
                + ", cost=" + totalCost
                + ", found=" + found()
                + ", algorithm=" + algorithm + ']';
    }
}

