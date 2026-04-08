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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * L1 Unit Tests: {@link AlgoOrchestrator}.
 *
 * @since 0.5.0
 */
@DisplayName("L1: AlgoOrchestrator — delegation, error codes, fail-if-not-found flag")
class AlgoOrchestratorTest {

    // =========================================================================
    // Stub PathFinder
    // =========================================================================

    private static final UUID SOURCE = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID TARGET = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final UUID MIDDLE = UUID.fromString("00000000-0000-0000-0000-000000000003");

    private static final class StubPathFinder implements PathFinder {
        private final boolean reachable;

        StubPathFinder(boolean reachable) { this.reachable = reachable; }

        @Override
        public PathResult findShortestPath(UUID src, UUID tgt, EdgeWeightFunction fn) {
            if (reachable) {
                return new PathResult(src, tgt, List.of(src, MIDDLE, tgt), 2.0, 0, "stub");
            }
            return PathResult.notFound(src, tgt, "stub");
        }

        @Override
        public Map<UUID, PathResult> findShortestPaths(UUID src, Set<UUID> targets, EdgeWeightFunction fn) {
            if (reachable) {
                PathResult r = new PathResult(src, targets.iterator().next(),
                        List.of(src, targets.iterator().next()), 1.0, 0, "stub");
                return Map.of(targets.iterator().next(), r);
            }
            return Map.of();
        }

        @Override
        public List<PathResult> findKShortestPaths(UUID src, UUID tgt, int k, EdgeWeightFunction fn) {
            if (reachable) {
                return List.of(new PathResult(src, tgt, List.of(src, tgt), 1.0, 0, "stub"));
            }
            return List.of();
        }

        @Override public boolean pathExists(UUID src, UUID tgt) { return reachable; }
        @Override public String algorithmName() { return "stub"; }
    }

    private AlgoOrchestrator reachableOrchestrator;
    private AlgoOrchestrator unreachableOrchestrator;

    @BeforeEach
    void setUp() {
        reachableOrchestrator   = new AlgoOrchestrator(new StubPathFinder(true));
        unreachableOrchestrator = new AlgoOrchestrator(new StubPathFinder(false));
    }

    // =========================================================================
    // Constructor
    // =========================================================================

    @Test
    @DisplayName("null pathFinder → NullPointerException")
    void nullPathFinderRejected() {
        assertThatThrownBy(() -> new AlgoOrchestrator(null))
                .isInstanceOf(NullPointerException.class);
    }

    // =========================================================================
    // findShortestPath
    // =========================================================================

    @Nested
    @DisplayName("findShortestPath()")
    class FindShortestPath {

        @Test
        @DisplayName("path found → PathResult.found() is true")
        void foundResult() {
            PathResult result = reachableOrchestrator.findShortestPath(SOURCE, TARGET);
            assertThat(result.found()).isTrue();
            assertThat(result.hopCount()).isEqualTo(2);
        }

        @Test
        @DisplayName("no path → PathResult.found() is false (lenient mode)")
        void notFoundLenient() {
            PathResult result = unreachableOrchestrator.findShortestPath(SOURCE, TARGET);
            assertThat(result.found()).isFalse();
        }

        @Test
        @DisplayName("no path + failIfNotFound=true → PathNotFoundException (EX-GRPH-5004)")
        void notFoundStrictMode() {
            assertThatThrownBy(() -> unreachableOrchestrator
                    .findShortestPath(SOURCE, TARGET, EdgeWeightFunction.DEFAULT, true))
                    .isInstanceOf(PathNotFoundException.class);
        }

        @Test
        @DisplayName("null source → NullPointerException")
        void nullSourceRejected() {
            assertThatThrownBy(() -> reachableOrchestrator.findShortestPath(null, TARGET))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("null target → NullPointerException")
        void nullTargetRejected() {
            assertThatThrownBy(() -> reachableOrchestrator.findShortestPath(SOURCE, null))
                    .isInstanceOf(NullPointerException.class);
        }
    }

    // =========================================================================
    // findShortestPaths (multi-target)
    // =========================================================================

    @Nested
    @DisplayName("findShortestPaths()")
    class FindShortestPaths {

        @Test
        @DisplayName("reachable targets → non-empty map")
        void reachableTargets() {
            Map<UUID, PathResult> results = reachableOrchestrator.findShortestPaths(
                    SOURCE, Set.of(TARGET), EdgeWeightFunction.DEFAULT);
            assertThat(results).isNotEmpty();
        }

        @Test
        @DisplayName("unreachable targets → empty map")
        void unreachableTargets() {
            Map<UUID, PathResult> results = unreachableOrchestrator.findShortestPaths(
                    SOURCE, Set.of(TARGET), EdgeWeightFunction.DEFAULT);
            assertThat(results).isEmpty();
        }

        @Test
        @DisplayName("null source → NullPointerException")
        void nullSourceRejected() {
            assertThatThrownBy(() -> reachableOrchestrator.findShortestPaths(
                    null, Set.of(TARGET), EdgeWeightFunction.DEFAULT))
                    .isInstanceOf(NullPointerException.class);
        }
    }

    // =========================================================================
    // findKShortestPaths
    // =========================================================================

    @Nested
    @DisplayName("findKShortestPaths()")
    class FindKShortestPaths {

        @Test
        @DisplayName("paths found → list is non-empty")
        void foundPaths() {
            List<PathResult> paths = reachableOrchestrator.findKShortestPaths(
                    SOURCE, TARGET, 3, EdgeWeightFunction.DEFAULT);
            assertThat(paths).isNotEmpty();
        }

        @Test
        @DisplayName("no paths found → empty list")
        void noPaths() {
            List<PathResult> paths = unreachableOrchestrator.findKShortestPaths(
                    SOURCE, TARGET, 3, EdgeWeightFunction.DEFAULT);
            assertThat(paths).isEmpty();
        }

        @Test
        @DisplayName("maxPaths=0 → IllegalArgumentException")
        void zeroMaxPathsRejected() {
            assertThatThrownBy(() -> reachableOrchestrator.findKShortestPaths(
                    SOURCE, TARGET, 0, EdgeWeightFunction.DEFAULT))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("maxPaths must be >= 1");
        }
    }

    // =========================================================================
    // pathExists
    // =========================================================================

    @Nested
    @DisplayName("pathExists()")
    class PathExists {

        @Test
        @DisplayName("returns true when path finder reports reachable")
        void trueWhenReachable() {
            assertThat(reachableOrchestrator.pathExists(SOURCE, TARGET)).isTrue();
        }

        @Test
        @DisplayName("returns false when path finder reports unreachable")
        void falseWhenUnreachable() {
            assertThat(unreachableOrchestrator.pathExists(SOURCE, TARGET)).isFalse();
        }
    }

    // =========================================================================
    // algorithmName passthrough
    // =========================================================================

    @Test
    @DisplayName("algorithmName() delegates to PathFinder")
    void algorithmNamePassthrough() {
        assertThat(reachableOrchestrator.algorithmName()).isEqualTo("stub");
    }
}

