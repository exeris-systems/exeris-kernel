/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.tck.contract.graph;

import eu.exeris.kernel.spi.graph.GraphDialect;
import eu.exeris.kernel.spi.graph.model.GraphEdgeDescriptor;
import eu.exeris.kernel.spi.graph.model.GraphNodeDescriptor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * TCK: Dialect Parity contract — verifies that a {@link GraphDialect} implementation
 * produces structurally valid, non-blank query strings for all DSL operations.
 *
 * <h2>Purpose (graph.md §Testing Strategy)</h2>
 * <p>Every driver implementation (Community PostgreSQL/PGQ, Community Neo4j/Cypher,
 * Enterprise PG native, Enterprise FFM-Bolt) MUST pass this TCK to prove that its
 * dialect implementation handles the full range of MATCH DSL inputs. The TCK does
 * NOT assert exact SQL/Cypher syntax — that is driver-specific. It asserts:
 * <ol>
 *   <li>No blank or null strings are returned for valid inputs</li>
 *   <li>{@link GraphDialect#dialectName()} is non-blank</li>
 *   <li>Multi-hop bounds are reflected in the output — checked by asserting that two
 *       different {@code (minHops, maxHops)} pairs produce different query text, not by
 *       parsing the hop numbers back out of it</li>
 *   <li>DDL generation is idempotent for the same schema inputs</li>
 * </ol>
 *
 * @since 0.5
 */
@DisplayName("TCK: GraphDialect — MATCH DSL parity contract")
public abstract class AbstractGraphDialectTck {

    /**
     * Creates the contract; subclasses supply the {@link GraphDialect} implementation under test via
     * {@link #createDialect()}.
     */
    public AbstractGraphDialectTck() {
        // Declared, not added: the implicit no-arg constructor, written out so it can carry a comment.
        super();
    }

    /**
     * Creates a dialect instance under test (PostgreSQL PGQ, Cypher, etc.).
     *
     * @return the dialect implementation under test
     */
    protected abstract GraphDialect createDialect();

    // =========================================================================
    // Shared fixtures
    // =========================================================================

    /**
     * Returns a shared {@code FOLLOWS} edge descriptor fixture, {@code User} to {@code User}.
     *
     * @return the {@code FOLLOWS} edge descriptor fixture
     */
    protected GraphEdgeDescriptor follows() {
        return GraphEdgeDescriptor.create("User", "FOLLOWS", "User");
    }

    /**
     * Returns a shared, weighted, bidirectional {@code SIMILAR_TO} edge descriptor fixture
     * between {@code Product} nodes.
     *
     * @return the {@code SIMILAR_TO} edge descriptor fixture
     */
    protected GraphEdgeDescriptor similarTo() {
        return new GraphEdgeDescriptor("Product", "SIMILAR_TO", "Product",
                0.8, true, GraphEdgeDescriptor.Direction.BOTH, "similar_to_edges");
    }

    /**
     * Returns a shared {@code User} node descriptor fixture.
     *
     * @return the {@code User} node descriptor fixture
     */
    protected GraphNodeDescriptor userNode() {
        return GraphNodeDescriptor.create("User", "users");
    }

    // =========================================================================
    // Single-hop MATCH
    // =========================================================================

    @Nested
    @DisplayName("buildMatchQuery()")
    class BuildMatchQuery {

        /**
         * Groups the assertions for {@code buildMatchQuery()}.
         */
        BuildMatchQuery() {
            // Declared, not added: the implicit no-arg constructor, written out so it can carry a comment.
            super();
        }

        @Test
        @DisplayName("returns non-blank string for a valid edge descriptor")
        void nonBlankForValidEdge() {
            GraphDialect dialect = createDialect();
            String query = dialect.buildMatchQuery(follows());
            assertThat(query).isNotNull().isNotBlank();
        }

        @Test
        @DisplayName("contains edge type identifier in the output")
        void containsEdgeType() {
            GraphDialect dialect = createDialect();
            String query = dialect.buildMatchQuery(follows());
            assertThat(query.toUpperCase(java.util.Locale.ROOT)).contains("FOLLOWS");
        }
    }

    // =========================================================================
    // Multi-hop MATCH
    // =========================================================================

    @Nested
    @DisplayName("buildMultiHopQuery()")
    class BuildMultiHopQuery {

        /**
         * Groups the assertions for {@code buildMultiHopQuery()}.
         */
        BuildMultiHopQuery() {
            // Declared, not added: the implicit no-arg constructor, written out so it can carry a comment.
            super();
        }

        @Test
        @DisplayName("returns non-blank string for valid bounds [1..3]")
        void nonBlankForValidBounds() {
            GraphDialect dialect = createDialect();
            String query = dialect.buildMultiHopQuery(follows(), 1, 3);
            assertThat(query).isNotNull().isNotBlank();
        }

        @Test
        @DisplayName("output for [1..3] differs from output for [1..5]")
        void differentBoundsProduceDifferentQueries() {
            GraphDialect dialect = createDialect();
            String q3 = dialect.buildMultiHopQuery(follows(), 1, 3);
            String q5 = dialect.buildMultiHopQuery(follows(), 1, 5);
            assertThat(q3).isNotEqualTo(q5);
        }
    }

    // =========================================================================
    // Shortest-path query
    // =========================================================================

    @Nested
    @DisplayName("buildShortestPathQuery()")
    class BuildShortestPathQuery {

        /**
         * Groups the assertions for {@code buildShortestPathQuery()}.
         */
        BuildShortestPathQuery() {
            // Declared, not added: the implicit no-arg constructor, written out so it can carry a comment.
            super();
        }

        @Test
        @DisplayName("returns non-blank string for valid edge and maxDepth")
        void nonBlankForValidInput() {
            GraphDialect dialect = createDialect();
            String query = dialect.buildShortestPathQuery(follows(), 5);
            assertThat(query).isNotNull().isNotBlank();
        }

        @Test
        @DisplayName("different maxDepth values produce different query strings")
        void depthIsReflectedInOutput() {
            GraphDialect dialect = createDialect();
            String q5  = dialect.buildShortestPathQuery(follows(), 5);
            String q10 = dialect.buildShortestPathQuery(follows(), 10);
            assertThat(q5).isNotEqualTo(q10);
        }
    }

    // =========================================================================
    // DDL generation
    // =========================================================================

    @Nested
    @DisplayName("DDL generation")
    class DdlGeneration {

        /**
         * Groups the assertions for DDL generation.
         */
        DdlGeneration() {
            // Declared, not added: the implicit no-arg constructor, written out so it can carry a comment.
            super();
        }

        @Test
        @DisplayName("buildCreatePropertyGraph() returns non-null string")
        void createPropertyGraphNonNull() {
            GraphDialect dialect = createDialect();
            String ddl = dialect.buildCreatePropertyGraph(
                    List.of(userNode()),
                    List.of(follows())
            );
            assertThat(ddl).isNotNull();
        }

        @Test
        @DisplayName("buildCreatePropertyGraph() is idempotent for identical input")
        void createPropertyGraphIdempotent() {
            GraphDialect dialect = createDialect();
            String ddl1 = dialect.buildCreatePropertyGraph(List.of(userNode()), List.of(follows()));
            String ddl2 = dialect.buildCreatePropertyGraph(List.of(userNode()), List.of(follows()));
            assertThat(ddl1).isEqualTo(ddl2);
        }

        @Test
        @DisplayName("buildCreateEdgeTable() returns non-blank string")
        void createEdgeTableNonBlank() {
            GraphDialect dialect = createDialect();
            String ddl = dialect.buildCreateEdgeTable(follows());
            assertThat(ddl).isNotNull().isNotBlank();
        }

        @Test
        @DisplayName("buildDropPropertyGraph() returns non-null string")
        void dropPropertyGraphNonNull() {
            GraphDialect dialect = createDialect();
            assertThat(dialect.buildDropPropertyGraph()).isNotNull();
        }
    }

    // =========================================================================
    // Dialect identity
    // =========================================================================

    @Test
    @DisplayName("dialectName() is non-blank")
    void dialectNameNonBlank() {
        GraphDialect dialect = createDialect();
        assertThat(dialect.dialectName()).isNotNull().isNotBlank();
    }

    // =========================================================================
    // Protocol-blind traversal contract (graph.md §1 Intent over Implementation)
    // =========================================================================

    @Test
    @DisplayName("identical traversal produces identical query strings (deterministic transpilation)")
    void deterministicTranspilation() {
        GraphDialect dialect = createDialect();
        GraphEdgeDescriptor edge = follows();
        String q1 = dialect.buildMatchQuery(edge);
        String q2 = dialect.buildMatchQuery(edge);
        assertThat(q1).isEqualTo(q2);
    }
}

