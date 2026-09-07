/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.core.graph;

import eu.exeris.kernel.spi.graph.GraphDialect;
import eu.exeris.kernel.spi.graph.GraphSession;
import eu.exeris.kernel.spi.graph.model.GraphEdgeDescriptor;
import eu.exeris.kernel.spi.graph.model.GraphTraversal;

import java.util.Objects;

/**
 * Core: Semantic synthesis engine — transpiles a MATCH DSL traversal request into a
 * target-specific database dialect string.
 *
 * <h2>Intent over Implementation (graph.md §Core Philosophy)</h2>
 * <p>This class is the "brain" of the MATCH abstraction. It receives a protocol-blind
 * {@link GraphTraversal} and delegates production of the final query string to the
 * active {@link GraphDialect} bound to the {@link eu.exeris.kernel.spi.graph.GraphEngine}.
 * The result is ready for execution by a {@link GraphSession} without the session knowing
 * <em>anything</em> about the originating DSL structure.
 *
 * <h2>Supported Target Dialects (The Wall — SPI-blind)</h2>
 * <ul>
 *   <li><b>SQL:2023 PGQ</b> — {@code GRAPH_TABLE(…) MATCH (…) WHERE …} for PostgreSQL ≥ 18</li>
 *   <li><b>Cypher</b>       — {@code MATCH (n)-[:FOLLOWS]->(m) WHERE …} for Neo4j / Memgraph</li>
 * </ul>
 * <p>The transpiler itself is dialect-blind — it calls {@link GraphDialect} methods and
 * never hard-codes SQL or Cypher syntax. The dialect implementation (Community/Enterprise)
 * is selected by the kernel at boot via ServiceLoader.
 *
 * <h2>Multi-Hop Semantics</h2>
 * <ul>
 *   <li>{@code maxDepth == 1} → {@link GraphDialect#buildMatchQuery(GraphEdgeDescriptor)}
 *       (single-hop, most optimised path)</li>
 *   <li>{@code maxDepth &gt; 1} → {@link GraphDialect#buildMultiHopQuery(GraphEdgeDescriptor, int, int)}
 *       with {@code minHops=1, maxHops=maxDepth}</li>
 * </ul>
 *
 * <h2>Zero-Allocation Contract</h2>
 * <p>This class holds no mutable state. Every call is pure function: input record in,
 * {@code String} out. No heap objects are created beyond the returned {@code String}
 * (which is owned by the caller). The transpiler instance is safe to share across
 * all virtual threads (it is effectively stateless after construction).
 *
 * @since 0.5
 */
public final class MatchDslTranspiler {

    private static final int SINGLE_HOP_DEPTH = 1;
    private static final int MIN_DEPTH        = 1;
    private static final int MIN_HOPS         = 1;

    private final GraphDialect dialect;

    /**
     * Constructs the transpiler bound to the given dialect.
     *
     * @param dialect active graph dialect for the current engine (must not be {@code null})
     */
    public MatchDslTranspiler(GraphDialect dialect) {
        this.dialect = Objects.requireNonNull(dialect, "dialect must not be null");
    }

    /**
     * Transpiles a BFS traversal request to a dialect-specific query string.
     *
     * <p>Dispatch table:
     * <ul>
     *   <li>{@code maxDepth == 1} → single-hop MATCH</li>
     *   <li>{@code maxDepth &gt; 1} → variable-length MATCH with bounds
     *       {@code [1..maxDepth]}</li>
     * </ul>
     *
     * @param traversal immutable traversal request (must not be {@code null})
     * @return dialect-specific query string ready for execution; never {@code null}
     */
    public String transpileBfs(GraphTraversal traversal) {
        Objects.requireNonNull(traversal, "traversal must not be null");
        GraphEdgeDescriptor edge = traversal.edgeDescriptor();
        int maxDepth = traversal.maxDepth();
        if (maxDepth == SINGLE_HOP_DEPTH) {
            return dialect.buildMatchQuery(edge);
        }
        return dialect.buildMultiHopQuery(edge, MIN_HOPS, maxDepth);
    }

    /**
     * Transpiles a shortest-path request to a dialect-specific query string.
     *
     * @param edge     edge type to traverse (must not be {@code null})
     * @param maxDepth maximum path depth (≥ 1)
     * @return dialect-specific shortest-path query; never {@code null}
     */
    public String transpileShortestPath(GraphEdgeDescriptor edge, int maxDepth) {
        Objects.requireNonNull(edge, "edge must not be null");
        if (maxDepth < MIN_DEPTH) {
            throw new IllegalArgumentException("maxDepth must be >= 1, got: " + maxDepth);
        }
        return dialect.buildShortestPathQuery(edge, maxDepth);
    }

    /**
     * Returns the name of the active dialect for diagnostics and JFR events.
     *
     * @return dialect name (e.g. "SQL/PGQ", "Cypher")
     */
    public String dialectName() {
        return dialect.dialectName();
    }
}






