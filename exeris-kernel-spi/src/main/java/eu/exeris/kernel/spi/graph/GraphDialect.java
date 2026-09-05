/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.spi.graph;

import eu.exeris.kernel.spi.graph.model.GraphEdgeDescriptor;
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
 * <h2>Implementations</h2>
 * <ul>
 *   <li><b>Community PostgreSQL:</b> SQL:2023 PGQ (GRAPH_TABLE + MATCH)</li>
 *   <li><b>Community Neo4j:</b> Cypher query language</li>
 *   <li><b>Enterprise PostgreSQL:</b> PGQ via PG native wire protocol</li>
 *   <li><b>Enterprise Neo4j:</b> FFM-native Bolt (future)</li>
 * </ul>
 *
 * @since 0.5
 */
public interface GraphDialect {

    /**
     * Builds a MATCH query for single-hop edge traversal.
     *
     * @param edge edge descriptor
     * @return dialect-specific query string
     */
    String buildMatchQuery(GraphEdgeDescriptor edge);

    /**
     * Builds a multi-hop MATCH query with depth bounds.
     *
     * @param edge    edge descriptor
     * @param minHops minimum hops (≥ 1)
     * @param maxHops maximum hops
     * @return dialect-specific query string
     */
    String buildMultiHopQuery(GraphEdgeDescriptor edge, int minHops, int maxHops);

    /**
     * Builds a shortest-path query.
     *
     * @param edge     edge descriptor
     * @param maxDepth maximum path depth
     * @return dialect-specific query string
     */
    String buildShortestPathQuery(GraphEdgeDescriptor edge, int maxDepth);

    /**
     * Generates DDL to create the property graph (if supported by backend).
     *
     * @param nodes node definitions
     * @param edges edge definitions
     * @return DDL statement, or empty string if not applicable
     */
    String buildCreatePropertyGraph(List<GraphNodeDescriptor> nodes, List<GraphEdgeDescriptor> edges);

    /**
     * Generates DDL to create an edge storage table.
     *
     * @param edge edge descriptor
     * @return DDL statement
     */
    String buildCreateEdgeTable(GraphEdgeDescriptor edge);

    /**
     * Generates DDL to drop the property graph (if supported by backend).
     *
     * @return DDL statement, or empty string if not applicable
     */
    String buildDropPropertyGraph();

    /**
     * Returns the dialect name for diagnostics and JFR events.
     *
     * @return dialect identifier (e.g. "SQL/PGQ", "Cypher")
     */
    String dialectName();
}

