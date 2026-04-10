/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.community.graph;

import eu.exeris.kernel.spi.graph.GraphDialect;
import eu.exeris.kernel.spi.graph.model.GraphEdgeDescriptor;
import eu.exeris.kernel.spi.graph.model.GraphNodeDescriptor;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Community Graph Dialect — SQL query and DDL generator for PostgreSQL.
 *
 * <p>Generates standard recursive CTE queries for traversal/shortest-path (SQL/PGQ mode)
 * and Cypher queries (Cypher mode). DDL uses plain PostgreSQL syntax.
 * Enterprise may use raw PG wire protocol to send the same SQL without JDBC overhead.
 *
 * @since 0.5.0
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

    @Override
    public String buildDropPropertyGraph() {
        if (mode == Mode.CYPHER) {
            return "// Cypher backend drop graph requested for " + graphName;
        }
        return "DROP PROPERTY GRAPH IF EXISTS " + requireSqlIdentifier(graphName);
    }

    @Override
    public String dialectName() {
        return mode == Mode.CYPHER ? CYPHER_DIALECT_NAME : SQL_DIALECT_NAME;
    }

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
