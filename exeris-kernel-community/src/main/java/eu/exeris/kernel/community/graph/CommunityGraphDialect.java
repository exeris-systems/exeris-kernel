/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.graph;

import eu.exeris.kernel.spi.graph.GraphDialect;
import eu.exeris.kernel.spi.graph.model.GraphEdgeDescriptor;
import eu.exeris.kernel.spi.graph.model.GraphHop;
import eu.exeris.kernel.spi.graph.model.GraphNodeDescriptor;

import java.util.List;
import java.util.Locale;
import java.util.Objects;
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

    /* default */ static final int MAX_PATH_LENGTH = 64;

    private static final String SQL_DIALECT_NAME = "SQL/PGQ";
    private static final String CYPHER_DIALECT_NAME = "Cypher";
    private static final Pattern SQL_IDENTIFIER_PATTERN = Pattern.compile("^[A-Za-z][A-Za-z0-9_]*$");
    private static final Pattern CYPHER_IDENTIFIER_PATTERN = Pattern.compile("^[A-Za-z]\\w*$");
    private static final int INITIAL_PATH_BUFFER_CAPACITY = 512;
    private static final int SINGLE_STEP = 1;
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
     * Builds a query that executes an ordered sequence of hops with walk semantics.
     *
     * <p>Separate {@code MATCH} clauses in Cypher decouple relationship uniqueness across steps,
     * matching the walk semantics produced by SQL recursive CTEs, with {@code WITH DISTINCT}
     * maintaining set semantics for each intermediate frontier.
     *
     * @param hops ordered hops, not empty
     * @return dialect-specific query string
     * @throws NullPointerException if {@code hops} is null
     * @throws IllegalArgumentException if {@code hops} is empty, or the sum of {@code maxRepeat}
     *         exceeds {@link #MAX_PATH_LENGTH}, or any identifier fails validation
     */
    @Override
    public String buildPathQuery(List<GraphHop> hops) {
        Objects.requireNonNull(hops, "hops must not be null");
        if (hops.isEmpty()) {
            throw new IllegalArgumentException("hops must not be empty");
        }
        long total = 0;
        for (GraphHop h : hops) {
            total += h.maxRepeat();
        }
        if (total > MAX_PATH_LENGTH) {
            throw new IllegalArgumentException(
                    "path length bound (sum of maxRepeat) exceeds the Community graph limit of " + MAX_PATH_LENGTH);
        }
        return mode == Mode.CYPHER ? buildCypherPathQuery(hops) : buildSqlPathQuery(hops);
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
        return buildPathQuery(List.of(GraphHop.of(edge)));
    }

    /**
     * {@inheritDoc}
     *
     * @throws IllegalArgumentException if {@code edge}'s relevant identifier (relationship
     *         type in Cypher mode, table name in SQL/PGQ mode) does not match
     *         {@code [A-Za-z][A-Za-z0-9_]*}, or if {@code minHops < 1} or {@code maxHops < minHops}
     */
    @Override
    public String buildMultiHopQuery(GraphEdgeDescriptor edge, int minHops, int maxHops) {
        return buildPathQuery(List.of(new GraphHop(edge, minHops, maxHops)));
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

    private static GraphEdgeDescriptor.Direction effective(GraphEdgeDescriptor edgeDescriptor) {
        return edgeDescriptor.bidirectional() ? GraphEdgeDescriptor.Direction.BOTH : edgeDescriptor.direction();
    }

    private String buildSqlPathQuery(List<GraphHop> hops) {
        StringBuilder queryBuilder = new StringBuilder(INITIAL_PATH_BUFFER_CAPACITY);
        queryBuilder.append("WITH RECURSIVE\nf0 (id) AS (SELECT CAST($1 AS uuid))");
        int hopIndex = 1;
        for (GraphHop hop : hops) {
            queryBuilder.append(",\n");
            String tableName = requireSqlIdentifier(hop.edge().tableName());
            GraphEdgeDescriptor.Direction dir = effective(hop.edge());
            int prevHop = hopIndex - 1;
            int minRepeat = hop.minRepeat();
            int maxRepeat = hop.maxRepeat();

            if (maxRepeat == SINGLE_STEP) {
                queryBuilder.append('f').append(hopIndex).append(" (id) AS (SELECT DISTINCT ")
                        .append(sqlNext("p", dir))
                        .append(" AS id FROM f").append(prevHop).append(" p INNER JOIN ")
                        .append(tableName).append(" e ON ")
                        .append(sqlCond("p", dir))
                        .append(')');
            } else {
                queryBuilder.append('h').append(hopIndex).append(" (id, depth) AS (SELECT p.id, 0 FROM f")
                        .append(prevHop).append(" p UNION SELECT ")
                        .append(sqlNext("h", dir))
                        .append(", h.depth + 1 FROM h").append(hopIndex).append(" h INNER JOIN ")
                        .append(tableName).append(" e ON ")
                        .append(sqlCond("h", dir))
                        .append(" WHERE h.depth < ").append(maxRepeat).append("),\nf")
                        .append(hopIndex).append(" (id) AS (SELECT DISTINCT id FROM h")
                        .append(hopIndex).append(" WHERE depth >= ").append(minRepeat).append(')');
            }
            hopIndex++;
        }
        queryBuilder.append("\nSELECT id FROM f").append(hops.size());
        return queryBuilder.toString();
    }

    private static String sqlCond(String alias, GraphEdgeDescriptor.Direction dir) {
        return switch (dir) {
            case OUTGOING -> "e.source_id = " + alias + ".id";
            case INCOMING -> "e.target_id = " + alias + ".id";
            case BOTH -> "(e.source_id = " + alias + ".id OR e.target_id = " + alias + ".id)";
        };
    }

    private static String sqlNext(String alias, GraphEdgeDescriptor.Direction dir) {
        return switch (dir) {
            case OUTGOING -> "e.target_id";
            case INCOMING -> "e.source_id";
            case BOTH -> "CASE WHEN e.source_id = " + alias + ".id THEN e.target_id ELSE e.source_id END";
        };
    }

    private String buildCypherPathQuery(List<GraphHop> hops) {
        int totalSteps = 0;
        for (GraphHop h : hops) {
            totalSteps += h.minRepeat();
            if (h.maxRepeat() > h.minRepeat()) {
                totalSteps += 1;
            }
        }
        final int finalStep = totalSteps;
        StringBuilder queryBuilder = new StringBuilder(INITIAL_PATH_BUFFER_CAPACITY);
        queryBuilder.append("MATCH (source) WHERE source.id = $sourceId\nWITH DISTINCT source");

        int stepIndex = 1;
        for (GraphHop hop : hops) {
            String edgeType = requireCypherIdentifier(hop.edge().edgeType());
            GraphEdgeDescriptor.Direction dir = effective(hop.edge());
            int minRepeat = hop.minRepeat();
            int maxRepeat = hop.maxRepeat();

            for (int r = 0; r < minRepeat; r++) {
                String prevVar = cypherVar(stepIndex - 1, finalStep);
                String currVar = cypherVar(stepIndex, finalStep);
                String arrow = cypherArrow(edgeType, dir, null);
                queryBuilder.append("\nMATCH (").append(prevVar).append(')')
                        .append(arrow).append('(').append(currVar).append(")\nWITH DISTINCT ").append(currVar);
                stepIndex++;
            }

            if (maxRepeat > minRepeat) {
                int range = maxRepeat - minRepeat;
                String prevVar = cypherVar(stepIndex - 1, finalStep);
                String currVar = cypherVar(stepIndex, finalStep);
                String arrow = cypherArrow(edgeType, dir, "*0.." + range);
                queryBuilder.append("\nMATCH (").append(prevVar).append(')')
                        .append(arrow).append('(').append(currVar).append(")\nWITH DISTINCT ").append(currVar);
                stepIndex++;
            }
        }
        queryBuilder.append("\nRETURN DISTINCT target.id AS id\n");
        return queryBuilder.toString();
    }

    private static String cypherVar(int step, int totalSteps) {
        if (step == 0) {
            return "source";
        }
        if (step == totalSteps) {
            return "target";
        }
        return "n" + step;
    }

    private static String cypherArrow(String edgeType, GraphEdgeDescriptor.Direction dir, String range) {
        String rangePart = range != null ? range : "";
        return switch (dir) {
            case OUTGOING -> "-[:" + edgeType + rangePart + "]->";
            case INCOMING -> "<-[:" + edgeType + rangePart + "]-";
            case BOTH -> "-[:" + edgeType + rangePart + "]-";
        };
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
