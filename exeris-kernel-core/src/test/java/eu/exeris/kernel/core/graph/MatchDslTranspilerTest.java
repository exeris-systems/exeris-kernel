/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.core.graph;

import eu.exeris.kernel.spi.graph.GraphDialect;
import eu.exeris.kernel.spi.graph.model.GraphEdgeDescriptor;
import eu.exeris.kernel.spi.graph.model.GraphNodeDescriptor;
import eu.exeris.kernel.spi.graph.model.GraphTraversal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * L1 Unit Tests: {@link MatchDslTranspiler}.
 *
 * @since 0.5.0
 */
@DisplayName("L1: MatchDslTranspiler — single-hop, multi-hop, shortest-path dispatch")
class MatchDslTranspilerTest {

    // =========================================================================
    // Stub dialect
    // =========================================================================

    private static final class StubDialect implements GraphDialect {
        @Override public String buildMatchQuery(GraphEdgeDescriptor edge) {
            return "MATCH_SINGLE:" + edge.edgeType();
        }
        @Override public String buildMultiHopQuery(GraphEdgeDescriptor edge, int min, int max) {
            return "MATCH_MULTI:" + edge.edgeType() + "[" + min + ".." + max + "]";
        }
        @Override public String buildShortestPathQuery(GraphEdgeDescriptor edge, int maxDepth) {
            return "SHORTEST:" + edge.edgeType() + "/" + maxDepth;
        }
        @Override public String buildCreatePropertyGraph(
                List<GraphNodeDescriptor> n, List<GraphEdgeDescriptor> e) { return "CREATE_PG"; }
        @Override public String buildCreateEdgeTable(GraphEdgeDescriptor edge) {
            return "CREATE_EDGE:" + edge.tableName();
        }
        @Override public String buildDropPropertyGraph() { return "DROP_PG"; }
        @Override public String dialectName() { return "StubDialect"; }
    }

    private MatchDslTranspiler transpiler;
    private GraphEdgeDescriptor follows;

    @BeforeEach
    void setUp() {
        transpiler = new MatchDslTranspiler(new StubDialect());
        follows    = GraphEdgeDescriptor.create("User", "FOLLOWS", "User");
    }

    // =========================================================================
    // Constructor guard
    // =========================================================================

    @Test
    @DisplayName("null dialect → NullPointerException on construction")
    void nullDialectRejected() {
        assertThatThrownBy(() -> new MatchDslTranspiler(null))
                .isInstanceOf(NullPointerException.class);
    }

    // =========================================================================
    // transpileBfs dispatch
    // =========================================================================

    @Nested
    @DisplayName("transpileBfs() dispatch")
    class TranspileBfs {

        @Test
        @DisplayName("maxDepth=1 routes to buildMatchQuery (single-hop fast path)")
        void singleHopRoutes() {
            GraphTraversal traversal = GraphTraversal.create(UUID.randomUUID(), follows, 1);
            String query = transpiler.transpileBfs(traversal);
            assertThat(query).isEqualTo("MATCH_SINGLE:FOLLOWS");
        }

        @Test
        @DisplayName("maxDepth=3 routes to buildMultiHopQuery with bounds [1..3]")
        void multiHopRoutes() {
            GraphTraversal traversal = GraphTraversal.create(UUID.randomUUID(), follows, 3);
            String query = transpiler.transpileBfs(traversal);
            assertThat(query).isEqualTo("MATCH_MULTI:FOLLOWS[1..3]");
        }

        @Test
        @DisplayName("maxDepth=10 routes to buildMultiHopQuery with bounds [1..10]")
        void maxDepthTenRoutes() {
            GraphTraversal traversal = GraphTraversal.create(UUID.randomUUID(), follows, 10);
            String query = transpiler.transpileBfs(traversal);
            assertThat(query).isEqualTo("MATCH_MULTI:FOLLOWS[1..10]");
        }

        @Test
        @DisplayName("null traversal → NullPointerException")
        void nullTraversalRejected() {
            assertThatThrownBy(() -> transpiler.transpileBfs(null))
                    .isInstanceOf(NullPointerException.class);
        }
    }

    // =========================================================================
    // transpileShortestPath dispatch
    // =========================================================================

    @Nested
    @DisplayName("transpileShortestPath() dispatch")
    class TranspileShortestPath {

        @Test
        @DisplayName("routes to buildShortestPathQuery with correct maxDepth")
        void routesCorrectly() {
            String query = transpiler.transpileShortestPath(follows, 5);
            assertThat(query).isEqualTo("SHORTEST:FOLLOWS/5");
        }

        @Test
        @DisplayName("maxDepth=1 is accepted as minimum valid value")
        void minDepthOne() {
            String query = transpiler.transpileShortestPath(follows, 1);
            assertThat(query).isEqualTo("SHORTEST:FOLLOWS/1");
        }

        @Test
        @DisplayName("maxDepth=0 → IllegalArgumentException")
        void zeroDepthRejected() {
            assertThatThrownBy(() -> transpiler.transpileShortestPath(follows, 0))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("maxDepth must be >= 1");
        }

        @Test
        @DisplayName("null edge → NullPointerException")
        void nullEdgeRejected() {
            assertThatThrownBy(() -> transpiler.transpileShortestPath(null, 3))
                    .isInstanceOf(NullPointerException.class);
        }
    }

    // =========================================================================
    // dialectName passthrough
    // =========================================================================

    @Test
    @DisplayName("dialectName() delegates to underlying dialect")
    void dialectNamePassthrough() {
        assertThat(transpiler.dialectName()).isEqualTo("StubDialect");
    }
}

