/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
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
 *   <li>Multi-hop bounds are reflected in the output (detected by min/max hop numbers)</li>
 *   <li>DDL generation is idempotent for the same schema inputs</li>
 * </ol>
 *
 * @since 0.5.0
 */
@DisplayName("TCK: GraphDialect — MATCH DSL parity contract")
public abstract class AbstractGraphDialectTck {

    /** Creates a dialect instance under test (PostgreSQL PGQ, Cypher, etc.). */
    protected abstract GraphDialect createDialect();

    // =========================================================================
    // Shared fixtures
    // =========================================================================

    protected GraphEdgeDescriptor follows() {
        return GraphEdgeDescriptor.create("User", "FOLLOWS", "User");
    }

    protected GraphEdgeDescriptor similarTo() {
        return new GraphEdgeDescriptor("Product", "SIMILAR_TO", "Product",
                0.8, true, GraphEdgeDescriptor.Direction.BOTH, "similar_to_edges");
    }

    protected GraphNodeDescriptor userNode() {
        return GraphNodeDescriptor.create("User", "users");
    }

    // =========================================================================
    // Single-hop MATCH
    // =========================================================================

    @Nested
    @DisplayName("buildMatchQuery()")
    class BuildMatchQuery {

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

