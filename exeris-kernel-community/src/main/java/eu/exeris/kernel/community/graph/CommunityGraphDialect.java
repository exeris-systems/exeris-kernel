/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.graph;

import eu.exeris.kernel.spi.graph.GraphDialect;
import eu.exeris.kernel.spi.graph.model.GraphEdgeDescriptor;
import eu.exeris.kernel.spi.graph.model.GraphNodeDescriptor;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Community-tier {@link GraphDialect}: generates SQL/PGQ query text and DDL for PostgreSQL,
 * or Cypher query text for a Cypher-speaking backend (Neo4j, Memgraph, FalkorDB) — chosen
 * once at construction from {@code backendType} and fixed for the dialect's lifetime.
 *
 * <p>Generates standard recursive CTE queries for traversal/shortest-path in SQL/PGQ mode,
 * and native Cypher queries in Cypher mode. DDL uses plain PostgreSQL syntax and is only
 * ever generated in SQL/PGQ mode — in Cypher mode the DDL-shaped methods return a comment
 * string instead, since Cypher backends manage node and edge storage implicitly.
 *
 * @since 0.5
 */
@SuppressWarnings({"PMD.CyclomaticComplexity", "PMD.TooManyMethods"})
// CyclomaticComplexity/TooManyMethods: two-dialect (SQL/PGQ + Cypher) factory; complexity is structural.
final class CommunityGraphDialect implements GraphDialect {

    private enum Mode {
        SQL_PGQ,
        CYPHER
    }

    private static final String SQL_DIALECT_NAME = "SQL/PGQ";
    private static final String CYPHER_DIALECT_NAME = "Cypher";
    private static final Pattern SQL_IDENTIFIER_PATTERN = Pattern.compile("^[A-Za-z][A-Za-z0-9_]*$");
    private static final Pattern CYPHER_IDENTIFIER_PATTERN = Pattern.compile("^[A-Za-z]\\w*$");
    private final String graphName;
    private final Mode mode;

    /* default */ CommunityGraphDialect(String graphName) {
        this(graphName, null);
    }

    /* default */ CommunityGraphDialect(String graphName, String backendType) {
        this.graphName = graphName;
        this.mode = isCypherBackend(backendType) ? Mode.CYPHER : Mode.SQL_PGQ;
    }

    /**
     * {@inheritDoc}
     *
     * @throws IllegalArgumentException if {@code edge}'s relevant identifier (relationship
     *         type in Cypher mode, table name in SQL/PGQ mode) does not match
     *         {@code [A-Za-z][A-Za-z0-9_]*}
     */
    @Override
    public String buildMatchQuery(GraphEdgeDescriptor edge) {
        if (mode == Mode.CYPHER) {
            return """
                    MATCH (source)-[:%s]->(target)
                    WHERE source.id = $sourceId
                    RETURN target.id AS target_id
                    """.formatted(requireCypherIdentifier(edge.edgeType()));
        }
        return """
                SELECT target_id FROM %s
                WHERE source_id = $1
                """.formatted(requireSqlIdentifier(edge.tableName()));
    }

    /**
     * {@inheritDoc}
     *
     * @throws IllegalArgumentException if {@code edge}'s relevant identifier (relationship
     *         type in Cypher mode, table name in SQL/PGQ mode) does not match
     *         {@code [A-Za-z][A-Za-z0-9_]*}
     */
    @Override
    public String buildMultiHopQuery(GraphEdgeDescriptor edge, int minHops, int maxHops) {
        if (mode == Mode.CYPHER) {
            return """
                    MATCH p = (source)-[:%s*%d..%d]->(target)
                    WHERE source.id = $sourceId
                    RETURN DISTINCT target.id AS id
                    """.formatted(requireCypherIdentifier(edge.edgeType()), minHops, maxHops);
        }
        String tableName = requireSqlIdentifier(edge.tableName());
        return """
                WITH RECURSIVE traversal AS (
                    SELECT target_id, 1 AS depth
                    FROM %s
                    WHERE source_id = $1
                    UNION ALL
                    SELECT e.target_id, t.depth + 1
                    FROM %s e
                    INNER JOIN traversal t ON e.source_id = t.target_id
                    WHERE t.depth < %d
                )
                SELECT DISTINCT target_id AS id FROM traversal WHERE depth >= %d
                """.formatted(tableName, tableName, maxHops, minHops);
    }

    /**
     * {@inheritDoc}
     *
     * @throws IllegalArgumentException if {@code edge}'s relevant identifier (relationship
     *         type in Cypher mode, table name in SQL/PGQ mode) does not match
     *         {@code [A-Za-z][A-Za-z0-9_]*}
     */
    @Override
    public String buildShortestPathQuery(GraphEdgeDescriptor edge, int maxDepth) {
        if (mode == Mode.CYPHER) {
            return """
                    MATCH p = (source)-[:%s*1..%d]->(target)
                    WHERE source.id = $sourceId
                      AND target.id = $targetId
                    RETURN [node IN nodes(p) | node.id] AS path,
                           reduce(
                               total = 0.0,
                               rel IN relationships(p) | total + coalesce(rel.weight, 1.0)
                           ) AS weight
                    ORDER BY weight ASC
                    LIMIT 1
                    """.formatted(requireCypherIdentifier(edge.edgeType()), maxDepth);
        }
        String tableName = requireSqlIdentifier(edge.tableName());
        return """
                WITH RECURSIVE path_search AS (
                    SELECT target_id, COALESCE(weight, 1.0), ARRAY[source_id, target_id] AS path, 1 AS depth
                    FROM %s
                    WHERE source_id = $1
                    UNION ALL
                    SELECT e.target_id, p.weight + COALESCE(e.weight, 1.0),
                           p.path || e.target_id, p.depth + 1
                    FROM %s e
                    INNER JOIN path_search p ON e.source_id = p.target_id
                    WHERE p.depth < %d AND NOT e.target_id = ANY(p.path)
                )
                SELECT path, weight FROM path_search
                WHERE target_id = $2
                ORDER BY weight ASC LIMIT 1
                """.formatted(tableName, tableName, maxDepth);
    }

    /**
     * {@inheritDoc}
     *
     * <p>In Cypher mode, returns a comment string and performs no identifier validation
     * (Cypher backends manage the graph namespace implicitly, so nothing here is spliced
     * into an executable query).
     *
     * @throws IllegalArgumentException in SQL/PGQ mode, if the graph name or any node's
     *         source table, label, or ID property, or any edge's table, relation type,
     *         source node, or target node does not match {@code [A-Za-z][A-Za-z0-9_]*}
     */
    @Override
    public String buildCreatePropertyGraph(List<GraphNodeDescriptor> nodeList,
                                           List<GraphEdgeDescriptor> edgeList) {
        if (mode == Mode.CYPHER) {
            return "// Cypher backend manages graph namespace implicitly for " + graphName;
        }
        StringBuilder builder = new StringBuilder(256);
        requireSqlIdentifier(graphName);
        builder.append("CREATE PROPERTY GRAPH IF NOT EXISTS ")
               .append(graphName)
               .append(" VERTEX TABLES (");

        for (int i = 0; i < nodeList.size(); i++) {
            if (i > 0) {
                builder.append(", ");
            }
            GraphNodeDescriptor node = nodeList.get(i);
            builder.append(requireSqlIdentifier(node.sourceTable()))
                   .append(" AS ")
                   .append(requireSqlIdentifier(node.nodeLabel()).toLowerCase(Locale.ROOT))
                   .append(" KEY (")
                   .append(requireSqlIdentifier(node.idProperty()))
                   .append(')');
        }

        builder.append(") EDGE TABLES (");

        for (int i = 0; i < edgeList.size(); i++) {
            if (i > 0) {
                builder.append(", ");
            }
            GraphEdgeDescriptor edge = edgeList.get(i);
            builder.append(requireSqlIdentifier(edge.tableName()))
                   .append(" AS ")
                   .append(requireSqlIdentifier(edge.edgeType()).toLowerCase(Locale.ROOT))
                   .append(" SOURCE KEY (source_id) REFERENCES ")
                   .append(requireSqlIdentifier(edge.sourceNode()).toLowerCase(Locale.ROOT))
                   .append(" DESTINATION KEY (target_id) REFERENCES ")
                   .append(requireSqlIdentifier(edge.targetNode()).toLowerCase(Locale.ROOT));
        }

        builder.append(");");
        return builder.toString();
    }

    /**
     * {@inheritDoc}
     *
     * <p>In Cypher mode, returns a comment string and performs no identifier validation.
     *
     * @throws IllegalArgumentException in SQL/PGQ mode, if {@code edge}'s table name does
     *         not match {@code [A-Za-z][A-Za-z0-9_]*}
     */
    @Override
    public String buildCreateEdgeTable(GraphEdgeDescriptor edge) {
        if (mode == Mode.CYPHER) {
            return "// Cypher backend edge label %s is schema-optional".formatted(edge.edgeType());
        }
        return """
                CREATE TABLE IF NOT EXISTS %s (
                    source_id  UUID NOT NULL,
                    target_id  UUID NOT NULL,
                    weight     DOUBLE PRECISION DEFAULT 1.0,
                    properties JSONB DEFAULT '{}',
                    created_at TIMESTAMPTZ DEFAULT NOW(),
                    PRIMARY KEY (source_id, target_id)
                )
                """.formatted(requireSqlIdentifier(edge.tableName()));
    }

    /**
     * Generates the DDL for the shared {@code graph_nodes} table (SQL/PGQ mode), or a
     * comment string (Cypher mode, which manages node storage implicitly). Not part of the
     * {@link GraphDialect} SPI — called directly by {@link CommunityGraphEngine} on this
     * concrete type.
     *
     * <p>Unlike {@link #buildCreateEdgeTable}, node storage is one shared table across every
     * label, not one table per label.
     *
     * @return the DDL statement, or a Cypher comment when in Cypher mode
     */
    /* default */ String buildCreateNodeTable() {
        if (mode == Mode.CYPHER) {
            return "// Cypher backend manages node storage implicitly";
        }
        return """
                CREATE TABLE IF NOT EXISTS graph_nodes (
                    id         UUID NOT NULL,
                    label      VARCHAR(255) NOT NULL,
                    properties JSONB DEFAULT '{}',
                    PRIMARY KEY (id)
                )
                """;
    }

    /**
     * {@inheritDoc}
     *
     * <p>In Cypher mode, returns a comment string and performs no identifier validation.
     *
     * @throws IllegalArgumentException in SQL/PGQ mode, if the graph name does not match
     *         {@code [A-Za-z][A-Za-z0-9_]*}
     */
    @Override
    public String buildDropPropertyGraph() {
        if (mode == Mode.CYPHER) {
            return "// Cypher backend drop graph requested for " + graphName;
        }
        return "DROP PROPERTY GRAPH IF EXISTS " + requireSqlIdentifier(graphName);
    }

    /**
     * Returns {@code "Cypher"} when this dialect was constructed in Cypher mode,
     * {@code "SQL/PGQ"} otherwise.
     *
     * @return the dialect identifier for diagnostics and JFR events
     */
    @Override
    public String dialectName() {
        return mode == Mode.CYPHER ? CYPHER_DIALECT_NAME : SQL_DIALECT_NAME;
    }

    /**
     * Returns whether this dialect was constructed in Cypher mode.
     *
     * @return {@code true} for a Cypher-speaking backend, {@code false} for SQL/PGQ
     */
    /* default */ boolean isCypherMode() {
        return mode == Mode.CYPHER;
    }

    private static boolean isCypherBackend(String backendType) {
        if (backendType == null) {
            return false;
        }
        return switch (backendType.toLowerCase(Locale.ROOT)) {
            case "neo4j", "memgraph", "falkordb" -> true;
            default -> false;
        };
    }


    private static String requireCypherIdentifier(String identifier) {
        if (identifier == null || !CYPHER_IDENTIFIER_PATTERN.matcher(identifier).matches()) {
            throw new IllegalArgumentException(
                    "Invalid Cypher identifier: expected [A-Za-z][A-Za-z0-9_]*, got: " + identifier);
        }
        return identifier;
    }

    private static String requireSqlIdentifier(String identifier) {
        if (identifier == null || !SQL_IDENTIFIER_PATTERN.matcher(identifier).matches()) {
            throw new IllegalArgumentException(
                    "Invalid SQL identifier: expected [A-Za-z][A-Za-z0-9_]*, got: " + identifier);
        }
        return identifier;
    }
}
