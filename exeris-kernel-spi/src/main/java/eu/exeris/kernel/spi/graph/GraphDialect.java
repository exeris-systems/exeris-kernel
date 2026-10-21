/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.spi.graph;

import eu.exeris.kernel.spi.graph.model.GraphEdgeDescriptor;
import eu.exeris.kernel.spi.graph.model.GraphHop;
import eu.exeris.kernel.spi.graph.model.GraphNodeDescriptor;

import java.util.List;

/**
 * SPI: Backend-specific query and DDL generation contract.
 *
 * <h2>The Wall (SPI Compliance)</h2>
 * <p>This interface is <strong>implementation-blind</strong>. It produces query strings
 * as opaque {@code String} values. It has zero knowledge of JDBC, Bolt, io_uring,
 * or any network/storage mechanism. Callers never see SQL vs Cypher — they see
 * "a query string the session knows how to execute".
 *
 * @implNote Community binds SQL:2023 PGQ ({@code GRAPH_TABLE}/{@code MATCH}) for PostgreSQL
 *           and Cypher for Neo4j. Enterprise binds PGQ over the native PostgreSQL wire
 *           protocol; an FFM-native Bolt binding is planned.
 * @since 0.5
 */
public interface GraphDialect {

    /**
     * Returns a dialect-specific query string that matches a single hop across {@code edge}.
     *
     * @param edge edge descriptor to match
     * @return dialect-specific query string
     */
    String buildMatchQuery(GraphEdgeDescriptor edge);

    /**
     * Returns a dialect-specific query string that matches between {@code minHops} and
     * {@code maxHops} hops across {@code edge}.
     *
     * <p>Equivalent to {@code buildPathQuery(List.of(new GraphHop(edge, minHops, maxHops)))}.
     *
     * @param edge    edge descriptor to match
     * @param minHops minimum hops (≥ 1)
     * @param maxHops maximum hops
     * @return dialect-specific query string
     */
    String buildMultiHopQuery(GraphEdgeDescriptor edge, int minHops, int maxHops);

    /**
     * Returns a dialect-specific query string that runs {@code hops} in order from one start node and yields
     * the terminal frontier, one id per row, with walk semantics and each hop's direction honoured (see
     * {@link GraphSession#traversePath}). {@link #buildMultiHopQuery} is the single-hop case.
     *
     * @param hops ordered hops, not empty
     * @return dialect-specific query string
     * @throws IllegalArgumentException if {@code hops} is empty, or an identifier fails the dialect's check
     * @implNote Community SQL/PGQ binds the start node as the only parameter {@code $1} and returns column
     *           {@code id}; Community Cypher binds {@code $sourceId} (string form) and returns {@code id}.
     * @since 0.13
     */
    String buildPathQuery(List<GraphHop> hops);

    /**
     * Returns a dialect-specific query string that finds the shortest path across
     * {@code edge}, bounded to at most {@code maxDepth} hops.
     *
     * @param edge     edge descriptor to match
     * @param maxDepth maximum path depth
     * @return dialect-specific query string
     */
    String buildShortestPathQuery(GraphEdgeDescriptor edge, int maxDepth);

    /**
     * Returns DDL that creates the property graph over {@code nodes} and {@code edges}.
     *
     * @param nodes node definitions
     * @param edges edge definitions
     * @return DDL statement, or an empty string if the backend has no explicit
     *         property-graph DDL
     */
    String buildCreatePropertyGraph(List<GraphNodeDescriptor> nodes, List<GraphEdgeDescriptor> edges);

    /**
     * Returns DDL that creates the storage table backing {@code edge}.
     *
     * @param edge edge descriptor
     * @return DDL statement
     */
    String buildCreateEdgeTable(GraphEdgeDescriptor edge);

    /**
     * Returns DDL that drops the property graph.
     *
     * @return DDL statement, or an empty string if the backend has no explicit
     *         property-graph DDL
     */
    String buildDropPropertyGraph();

    /**
     * Returns the dialect name for diagnostics and JFR events.
     *
     * @return dialect identifier (e.g. "SQL/PGQ", "Cypher")
     */
    String dialectName();
}

