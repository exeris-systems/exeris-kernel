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
 * Core: transpiles a {@link GraphTraversal} or a shortest-path request into a query string
 * by dispatching it to the matching builder of the {@link GraphDialect} it is constructed with.
 *
 * <h2>Not wired into any session or backend</h2>
 * <p>No {@link GraphSession}, {@link eu.exeris.kernel.spi.graph.GraphEngine} or graph backend
 * constructs or calls this class. A graph backend builds the query it executes by calling
 * {@link GraphDialect} itself, so nothing in the kernel executes a string returned here. The
 * class is exercised only by tests that call it directly and inspect the returned string.
 *
 * <h2>Dialect blindness</h2>
 * <p>This class writes no SQL or Cypher syntax of its own: every string it returns is the
 * return value of one builder method of the {@link GraphDialect} passed to its constructor. It
 * selects no dialect and takes no part in bootstrap.
 *
 * <h2>Multi-Hop Semantics</h2>
 * <ul>
 *   <li>{@code maxDepth == 1} → {@link GraphDialect#buildMatchQuery(GraphEdgeDescriptor)}
 *       (single-hop, most optimised path)</li>
 *   <li>{@code maxDepth &gt; 1} → {@link GraphDialect#buildMultiHopQuery(GraphEdgeDescriptor, int, int)}
 *       with {@code minHops=1, maxHops=maxDepth}</li>
 * </ul>
 *
 * <h2>Thread safety and allocation</h2>
 * <p>This class holds no mutable state after construction, and an instance may be shared
 * across threads. Apart from the exception it throws on an invalid argument it allocates
 * nothing of its own; what a call allocates is what the bound dialect allocates to build the
 * returned {@code String}.
 *
 * @since 0.5
 */
public final class MatchDslTranspiler {

    private static final int SINGLE_HOP_DEPTH = 1;
    private static final int MIN_DEPTH        = 1;
    private static final int MIN_HOPS         = 1;

    private final GraphDialect dialect;

    /**
     * Binds this transpiler to a single {@link GraphDialect}; every transpile call below
     * delegates the actual query-string construction to it.
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
     * @return the query string the bound dialect builds for {@code traversal}; never {@code null}
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
     * @throws IllegalArgumentException if {@code maxDepth} is less than 1
     */
    public String transpileShortestPath(GraphEdgeDescriptor edge, int maxDepth) {
        Objects.requireNonNull(edge, "edge must not be null");
        if (maxDepth < MIN_DEPTH) {
            throw new IllegalArgumentException("maxDepth must be >= 1, got: " + maxDepth);
        }
        return dialect.buildShortestPathQuery(edge, maxDepth);
    }

    /**
     * Returns the name of the dialect this transpiler is bound to, for diagnostics.
     *
     * @return dialect name (e.g. "SQL/PGQ", "Cypher")
     */
    public String dialectName() {
        return dialect.dialectName();
    }
}






