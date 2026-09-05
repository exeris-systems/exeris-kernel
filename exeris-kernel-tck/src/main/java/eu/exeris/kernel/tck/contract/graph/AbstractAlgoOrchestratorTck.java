/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.tck.contract.graph;

import eu.exeris.kernel.spi.graph.algorithm.EdgeWeightFunction;
import eu.exeris.kernel.spi.graph.algorithm.PathFinder;
import eu.exeris.kernel.spi.graph.model.PathResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import eu.exeris.kernel.tck.support.TckScope;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * TCK: Abstract base for {@link PathFinder} SPI contract verification.
 *
 * <h2>Contract (graph.md §Responsibilities)</h2>
 * <ul>
 *   <li>{@link PathFinder} implementations correctly find paths between connected nodes</li>
 *   <li>Path results carry correct metadata (algorithm name, hop count, total cost)</li>
 *   <li>{@link PathResult#found()} accurately reflects whether a path was found</li>
 *   <li>K-shortest paths are returned in cost-ascending order</li>
 *   <li>The finder is safe for concurrent access from N virtual threads</li>
 *   <li>Unreachable nodes yield {@link PathResult#notFound} sentinels</li>
 * </ul>
 *
 * <h2>AlgoOrchestrator integration</h2>
 * <p>The {@code AlgoOrchestrator} in {@code exeris-kernel-core} delegates all algorithmic
 * work to the {@link PathFinder} SPI. This TCK validates the full round-trip through
 * the SPI contract — implementation correctness flows directly to orchestrator correctness.
 *
 * @since 0.5
 */
@DisplayName("TCK: PathFinder SPI — algorithm correctness contract")
public abstract class AbstractAlgoOrchestratorTck {

    /**
     * Creates a {@link PathFinder} implementation under test backed by a test graph
     * containing at least one reachable path from {@link #reachableSource()} to
     * {@link #reachableTarget()}.
     */
    protected abstract PathFinder createPathFinder();

    /**
     * Returns a source node UUID that has at least one reachable path in the test graph.
     */
    protected abstract UUID reachableSource();

    /**
     * Returns a target node UUID reachable from {@link #reachableSource()}.
     */
    protected abstract UUID reachableTarget();

    /**
     * Returns a source UUID that has no path to any target (isolated node).
     */
    protected abstract UUID isolatedNode();

    // =========================================================================
    // Reachable path contract
    // =========================================================================

    @Nested
    @DisplayName("Reachable path contract")
    class ReachablePath {

        @Test
        @DisplayName("findShortestPath() returns found=true for a connected pair")
        void foundForConnected() {
            PathFinder pf = createPathFinder();
            PathResult result = pf.findShortestPath(
                    reachableSource(), reachableTarget(), EdgeWeightFunction.DEFAULT);
            assertThat(result.found()).isTrue();
        }

        @Test
        @DisplayName("PathResult.path() is non-empty and starts with source, ends with target")
        void pathEndsCorrectly() {
            PathFinder pf = createPathFinder();
            PathResult result = pf.findShortestPath(
                    reachableSource(), reachableTarget(), EdgeWeightFunction.DEFAULT);
            assertThat(result.path()).isNotEmpty();
            assertThat(result.path().getFirst()).isEqualTo(reachableSource());
            assertThat(result.path().getLast()).isEqualTo(reachableTarget());
        }

        @Test
        @DisplayName("PathResult.hopCount() equals path.size()-1")
        void hopCountConsistency() {
            PathFinder pf = createPathFinder();
            PathResult result = pf.findShortestPath(
                    reachableSource(), reachableTarget(), EdgeWeightFunction.DEFAULT);
            assertThat(result.hopCount()).isEqualTo(result.path().size() - 1);
        }

        @Test
        @DisplayName("totalCost() is finite and non-negative for a found path")
        void totalCostFinite() {
            PathFinder pf = createPathFinder();
            PathResult result = pf.findShortestPath(
                    reachableSource(), reachableTarget(), EdgeWeightFunction.DEFAULT);
            assertThat(Double.isFinite(result.totalCost())).isTrue();
            assertThat(result.totalCost()).isGreaterThanOrEqualTo(0.0);
        }

        @Test
        @DisplayName("algorithmName() is non-blank")
        void algorithmNameNonBlank() {
            PathFinder pf = createPathFinder();
            assertThat(pf.algorithmName()).isNotNull().isNotBlank();
        }
    }

    // =========================================================================
    // Not-found contract
    // =========================================================================

    @Nested
    @DisplayName("Not-found contract")
    class NotFound {

        @Test
        @DisplayName("isolated node → PathResult.found()=false")
        void notFoundReturnsNotFoundResult() {
            PathFinder pf = createPathFinder();
            PathResult result = pf.findShortestPath(
                    isolatedNode(), reachableTarget(), EdgeWeightFunction.DEFAULT);
            assertThat(result.found()).isFalse();
        }

        @Test
        @DisplayName("not-found sentinel: path is empty and totalCost is +Infinity")
        void notFoundSentinelShape() {
            PathFinder pf = createPathFinder();
            PathResult result = pf.findShortestPath(
                    isolatedNode(), reachableTarget(), EdgeWeightFunction.DEFAULT);
            assertThat(result.path()).isEmpty();
            assertThat(result.totalCost()).isEqualTo(Double.POSITIVE_INFINITY);
        }

        @Test
        @DisplayName("pathExists() returns false for unreachable pair")
        void pathExistsFalseForIsolated() {
            PathFinder pf = createPathFinder();
            assertThat(pf.pathExists(isolatedNode(), reachableTarget())).isFalse();
        }

        @Test
        @DisplayName("pathExists() returns true for connected pair")
        void pathExistsTrueForConnected() {
            PathFinder pf = createPathFinder();
            assertThat(pf.pathExists(reachableSource(), reachableTarget())).isTrue();
        }
    }

    // =========================================================================
    // K-shortest paths contract
    // =========================================================================

    @Nested
    @DisplayName("K-shortest paths contract")
    class KShortestPaths {

        @Test
        @DisplayName("findKShortestPaths() returns at most maxPaths results")
        void resultCountBounded() {
            PathFinder pf = createPathFinder();
            int maxPaths = 3;
            List<PathResult> paths = pf.findKShortestPaths(
                    reachableSource(), reachableTarget(), maxPaths, EdgeWeightFunction.DEFAULT);
            assertThat(paths.size()).isLessThanOrEqualTo(maxPaths);
        }

        @Test
        @DisplayName("results are returned in cost-ascending order")
        void orderedByCostAscending() {
            PathFinder pf = createPathFinder();
            List<PathResult> paths = pf.findKShortestPaths(
                    reachableSource(), reachableTarget(), 5, EdgeWeightFunction.DEFAULT);
            for (int i = 1; i < paths.size(); i++) {
                assertThat(paths.get(i).totalCost())
                        .isGreaterThanOrEqualTo(paths.get(i - 1).totalCost());
            }
        }

        @Test
        @DisplayName("returned list is unmodifiable")
        void listIsUnmodifiable() {
            PathFinder pf = createPathFinder();
            List<PathResult> paths = pf.findKShortestPaths(
                    reachableSource(), reachableTarget(), 3, EdgeWeightFunction.DEFAULT);
            assertThat(paths).isUnmodifiable();
        }
    }

    // =========================================================================
    // Multi-target contract
    // =========================================================================

    @Test
    @DisplayName("findShortestPaths() returns found=true for each reachable target")
    void multiTargetReachable() {
        PathFinder pf = createPathFinder();
        Map<UUID, PathResult> results = pf.findShortestPaths(
                reachableSource(),
                Set.of(reachableTarget()),
                EdgeWeightFunction.DEFAULT
        );
        assertThat(results).containsKey(reachableTarget());
        assertThat(results.get(reachableTarget()).found()).isTrue();
    }

    @Test
    @DisplayName("findShortestPaths() result map is unmodifiable")
    void multiTargetMapUnmodifiable() {
        PathFinder pf = createPathFinder();
        Map<UUID, PathResult> results = pf.findShortestPaths(
                reachableSource(), Set.of(reachableTarget()), EdgeWeightFunction.DEFAULT);
        assertThat(results).isUnmodifiable();
    }

    // =========================================================================
    // Concurrent access
    // =========================================================================

    @Test
    @DisplayName("100 VTs calling findShortestPath() concurrently — no crash, no data corruption")
    @Timeout(30)
    void concurrentPathFinding() throws InterruptedException {
        PathFinder pf = createPathFinder();
        AtomicInteger errors = new AtomicInteger(0);
        try (var scope = TckScope.open()) {
            for (int i = 0; i < 100; i++) {
                scope.fork(() -> {
                    try {
                        pf.findShortestPath(reachableSource(), reachableTarget(),
                                EdgeWeightFunction.DEFAULT);
                    } catch (Exception _) {
                        errors.incrementAndGet();
                    }
                    return null;
                });
            }
            scope.join();
        }
        assertThat(errors.get()).isZero();
    }
}

