/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.graph;

import eu.exeris.kernel.spi.context.KernelProviders;
import eu.exeris.kernel.spi.exceptions.graph.GraphQueryException;
import eu.exeris.kernel.spi.graph.model.GraphEdgeDescriptor;
import eu.exeris.kernel.spi.graph.model.GraphPathSpec;
import eu.exeris.kernel.spi.memory.LoanedBuffer;
import eu.exeris.kernel.spi.persistence.PersistenceConnection;
import eu.exeris.kernel.spi.persistence.PersistenceStatement;
import eu.exeris.kernel.spi.persistence.QueryResult;
import eu.exeris.kernel.spi.security.StorageContext.IsolationStrategy;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * SQL/PGQ backend adapter (Community's {@link CommunityGraphBackend} for the non-Cypher
 * dialect). Acquires a {@link PersistenceConnection} per operation from
 * {@code connectionSupplier} rather than holding one of its own, and does not override
 * {@link CommunityGraphBackend}'s transaction lifecycle defaults — each statement commits on
 * execution.
 */
// Backend adapter intentionally mirrors the GraphSession surface for the SQL dialect.
// TooManyMethods + CyclomaticComplexity + GodClass: SQL/PGQ backend adapter implementing the full
// CommunityGraphBackend surface — one method per graph operation type; complexity is structural
// (SQL query branching and statement lifecycle coordination).
@SuppressWarnings({"PMD.TooManyMethods", "PMD.CyclomaticComplexity", "PMD.GodClass"})
final class CommunityGraphSqlHelper implements CommunityGraphBackend {
    private static final String ROOT_NODE_QUERY_TYPE = "ROOT_NODE";
    private static final Pattern SQL_IDENTIFIER_PATTERN = Pattern.compile("^[A-Za-z]\\w*$");
    private static final int SIDE_SOURCE = 1;
    private static final int SIDE_TARGET = 2;
    private static final int INITIAL_SQL_CAPACITY = 256;
    private static final String BINDING_NAME = "Community SQL/PGQ (PostgreSQL)";
    private static final Set<IsolationStrategy> SCOPABLE =
            Collections.unmodifiableSet(EnumSet.of(IsolationStrategy.SEPARATED_SCHEMA, IsolationStrategy.DEDICATED));
    private static final Map<IsolationStrategy, String> SCOPE_REFUSAL_DETAILS;

    static {
        Map<IsolationStrategy, String> details = new EnumMap<>(IsolationStrategy.class);
        String scopableList = "SEPARATED_SCHEMA, DEDICATED";
        for (IsolationStrategy strategy : IsolationStrategy.values()) {
            details.put(strategy, BINDING_NAME + " cannot scope a graph query under isolation strategy "
                    + strategy + "; scopable strategies: " + scopableList);
        }
        SCOPE_REFUSAL_DETAILS = Collections.unmodifiableMap(details);
    }

    private final CommunityGraphDialect dialect;
    private final Supplier<PersistenceConnection> connectionSupplier;
    private final List<GraphEdgeDescriptor> registeredEdges;

    /* default */ CommunityGraphSqlHelper(
            CommunityGraphDialect dialect,
            Supplier<PersistenceConnection> connectionSupplier,
            List<GraphEdgeDescriptor> registeredEdges) {
        this.dialect = Objects.requireNonNull(dialect, "dialect must not be null");
        this.connectionSupplier = Objects.requireNonNull(
                connectionSupplier,
                "connectionSupplier must not be null");
        this.registeredEdges = Objects.requireNonNull(
                registeredEdges,
                "registeredEdges must not be null");
    }

    /**
     * Executes a path traversal as one statement and collects the terminal frontier node IDs.
     *
     * @param path path specification
     * @return node IDs in the terminal frontier
     * @throws GraphQueryException if query execution fails
     */
    // Dialect and JDBC driver throw RuntimeException; map to GraphQueryException
    @SuppressWarnings("PMD.AvoidCatchingGenericException")
    @Override
    public List<UUID> traversePath(GraphPathSpec path) {
        List<UUID> results = new ArrayList<>();
        try {
            String sql = dialect.buildPathQuery(path.hops());
            try (PersistenceConnection conn = acquireConnection();
                 PersistenceStatement stmt = conn.prepare(sql)) {
                stmt.bindUuid(0, path.startNodeId());
                try (QueryResult queryResult = stmt.executeQuery()) {
                    while (queryResult.next()) {
                        results.add(queryResult.row().getUuid(0));
                    }
                }
            }
        } catch (GraphQueryException e) {
            throw e;
        } catch (RuntimeException cause) {
            throw new GraphQueryException("PATH", "Failed to execute graph path traversal", cause);
        }
        return results;
    }

    /**
     * Executes a path traversal and streams the terminal frontier node IDs as a UTF-8 JSON array
     * into a network buffer from {@code KernelProviders.allocator()}.
     *
     * @param path path specification
     * @return a buffer holding the query's JSON text; caller-owned, caller must close it
     * @throws GraphQueryException if query execution fails
     */
    // Dialect and JDBC driver throw RuntimeException; map to GraphQueryException
    @SuppressWarnings("PMD.AvoidCatchingGenericException")
    @Override
    public LoanedBuffer streamPathJson(GraphPathSpec path) {
        byte[] jsonBytes;
        try {
            String sql = "SELECT json_agg(frontier.id) AS json_result FROM ("
                    + dialect.buildPathQuery(path.hops()) + ") AS frontier";
            try (PersistenceConnection conn = acquireConnection();
                 PersistenceStatement stmt = conn.prepare(sql)) {
                stmt.bindUuid(0, path.startNodeId());
                try (QueryResult queryResult = stmt.executeQuery()) {
                    if (queryResult.next()) {
                        String json = queryResult.row().getString(0);
                        jsonBytes = json != null
                                ? json.getBytes(StandardCharsets.UTF_8)
                                : CommunityGraphBufferOps.EMPTY_JSON_ARRAY;
                    } else {
                        jsonBytes = CommunityGraphBufferOps.EMPTY_JSON_ARRAY;
                    }
                }
            }
        } catch (GraphQueryException e) {
            throw e;
        } catch (RuntimeException cause) {
            throw new GraphQueryException("PATH", "Failed to execute graph path traversal", cause);
        }
        LoanedBuffer buffer = KernelProviders.allocator().allocateNetwork(jsonBytes.length);
        MemorySegment.copy(jsonBytes, 0, buffer.segment(), ValueLayout.JAVA_BYTE, 0, jsonBytes.length);
        buffer.setSize(jsonBytes.length);
        return buffer;
    }

    @Override
    public Set<IsolationStrategy> scopableStrategies() {
        return SCOPABLE;
    }

    @Override
    public String scopeRefusalDetail(IsolationStrategy strategy) {
        return SCOPE_REFUSAL_DETAILS.get(strategy);
    }

    /**
     * Inserts a new edge row. The edge table's primary key is
     * {@code (source_id, target_id)}, so a second call for the same pair fails on a
     * primary-key violation raised by the underlying persistence connection — unlike the
     * Cypher backend's {@code createEdge}, which allows parallel relationships of the same
     * type. Use {@link #upsertEdge} to update an existing edge instead.
     *
     * @param edge       edge descriptor naming the backing table
     * @param sourceId   source node ID
     * @param targetId   target node ID
     * @param weight     edge weight
     * @param properties JSON properties string; {@code null} is stored as {@code "{}"}
     * @throws eu.exeris.kernel.spi.exceptions.graph.GraphQueryException ({@code EX-GRPH-5002})
     *         if {@code edge}'s table name does not match {@code [A-Za-z][A-Za-z0-9_]*}
     */
    @Override
    public void createEdge(GraphEdgeDescriptor edge, UUID sourceId, UUID targetId,
                    double weight, String properties) {
        executeEdgeDml(buildInsertEdgeSql(edge), sourceId, targetId, weight, properties);
    }

    /**
     * Inserts a new edge row, or updates {@code weight} and {@code properties} on the
     * existing row when {@code (sourceId, targetId)} already has an edge in this table.
     *
     * @param edge       edge descriptor naming the backing table
     * @param sourceId   source node ID
     * @param targetId   target node ID
     * @param weight     edge weight
     * @param properties JSON properties string; {@code null} is stored as {@code "{}"}
     * @throws eu.exeris.kernel.spi.exceptions.graph.GraphQueryException ({@code EX-GRPH-5002})
     *         if {@code edge}'s table name does not match {@code [A-Za-z][A-Za-z0-9_]*}
     */
    @Override
    public void upsertEdge(GraphEdgeDescriptor edge, UUID sourceId, UUID targetId,
                    double weight, String properties) {
        executeEdgeDml(buildUpsertEdgeSql(edge), sourceId, targetId, weight, properties);
    }

    /**
     * Deletes the row for {@code (sourceId, targetId)} from {@code edge}'s table. A no-op if
     * no such row exists.
     *
     * @param edge     edge descriptor naming the backing table
     * @param sourceId source node ID
     * @param targetId target node ID
     * @throws eu.exeris.kernel.spi.exceptions.graph.GraphQueryException ({@code EX-GRPH-5002})
     *         if {@code edge}'s table name does not match {@code [A-Za-z][A-Za-z0-9_]*}
     */
    @Override
    public void deleteEdge(GraphEdgeDescriptor edge, UUID sourceId, UUID targetId) {
        String sql = "DELETE FROM %s WHERE source_id = $1 AND target_id = $2"
                .formatted(requireSqlIdentifier(edge.tableName()));
        try (PersistenceConnection conn = acquireConnection();
             PersistenceStatement stmt = conn.prepare(sql)) {
            stmt.bindUuid(0, sourceId);
            stmt.bindUuid(1, targetId);
            stmt.executeUpdate();
        }
    }

    /**
     * Inserts a new row in the shared {@code graph_nodes} table, or updates
     * {@code properties} when {@code nodeId} already has a row. {@code label} is bound as a
     * query parameter, so — unlike the Cypher backend, where a label is spliced into query
     * text and must pass {@link CypherIdentifiers#requireIdentifier} — it is not validated
     * against any identifier pattern here.
     *
     * @param label      node label
     * @param nodeId     node ID
     * @param properties encoded properties, decoded via
     *                   {@link CommunityGraphBufferOps#decodeProperties}; read but not
     *                   closed — the caller retains ownership. {@code null} is stored as
     *                   {@code "{}"}
     */
    @Override
    public void upsertNode(String label, UUID nodeId, LoanedBuffer properties) {
        String sql = """
                INSERT INTO graph_nodes (id, label, properties)
                VALUES ($1, $2, $3::jsonb)
                ON CONFLICT (id) DO UPDATE SET properties = EXCLUDED.properties
                """;
        try (PersistenceConnection conn = acquireConnection();
             PersistenceStatement stmt = conn.prepare(sql)) {
            stmt.bindUuid(0, nodeId);
            stmt.bindString(1, label);
            stmt.bindString(2, CommunityGraphBufferOps.decodeProperties(properties));
            stmt.executeUpdate();
        }
    }

    /**
     * Builds the SQL statement for deleting a node and cascading the deletion to all
     * connected edges across registered edge tables.
     *
     * <p>One statement is atomic without a transaction (the SQL backend has none):
     * PostgreSQL runs every data-modifying {@code WITH} sub-statement exactly once, to
     * completion, on one snapshot.
     *
     * <p>Edge rows carry no label; a node of label {@code L} is connected through the edges
     * whose descriptor names {@code L} on that side. That is exactly the set Neo4j's
     * {@code MATCH (n:L {id}) DETACH DELETE n} removes, because Cypher {@code createEdge}
     * {@code MERGE}s endpoints with the descriptor's labels. A label the node does not carry
     * therefore removes nothing on either backend.
     *
     * <p>Only registered edge descriptors are cascaded; an edge table created outside
     * {@code registerEdges} is not known to the engine. A registered table missing in the
     * current scope fails the whole statement (nothing is deleted).
     *
     * @param label node label to delete
     * @return the complete atomic delete SQL statement
     * @throws eu.exeris.kernel.spi.exceptions.graph.GraphQueryException ({@code EX-GRPH-5002})
     *         if a registered edge table naming {@code label} is not a valid SQL identifier
     */
    /* default */ String buildDeleteNodeSql(String label) {
        Map<String, Integer> sides = resolveCascadeSides(label);
        if (sides.isEmpty()) {
            return "WITH arg (id) AS (SELECT CAST($1 AS uuid))\n"
                    + "DELETE FROM graph_nodes WHERE id = (SELECT id FROM arg) AND label = $2";
        }
        StringBuilder sqlBuilder = new StringBuilder(INITIAL_SQL_CAPACITY);
        sqlBuilder.append("WITH arg (id) AS (SELECT CAST($1 AS uuid)),\n");
        int index = 0;
        int total = sides.size();
        for (Map.Entry<String, Integer> entry : sides.entrySet()) {
            String table = entry.getKey();
            int side = entry.getValue();
            String pred = deletePredicate(side);
            sqlBuilder.append("     del_e").append(index).append(" AS (DELETE FROM ").append(table)
                    .append(" WHERE ").append(pred).append(')');
            if (index < total - 1) {
                sqlBuilder.append(",\n");
            } else {
                sqlBuilder.append('\n');
            }
            index++;
        }
        sqlBuilder.append("DELETE FROM graph_nodes WHERE id = (SELECT id FROM arg) AND label = $2");
        return sqlBuilder.toString();
    }

    private Map<String, Integer> resolveCascadeSides(String label) {
        Map<String, Integer> sides = new LinkedHashMap<>();
        for (GraphEdgeDescriptor e : registeredEdges) {
            int side = (e.sourceNode().equals(label) ? SIDE_SOURCE : 0)
                    | (e.targetNode().equals(label) ? SIDE_TARGET : 0);
            if (side == 0) {
                continue;
            }
            sides.merge(requireSqlIdentifier(e.tableName()), side, (a, b) -> a | b);
        }
        return sides;
    }

    private static String deletePredicate(int side) {
        if (side == SIDE_SOURCE) {
            return "source_id = (SELECT id FROM arg)";
        }
        if (side == SIDE_TARGET) {
            return "target_id = (SELECT id FROM arg)";
        }
        return "source_id = (SELECT id FROM arg) OR target_id = (SELECT id FROM arg)";
    }

    /**
     * Deletes the row identified by {@code nodeId} and {@code label} from
     * {@code graph_nodes} and cascades the deletion to all connected edges in registered
     * edge tables. A no-op if no such row exists.
     *
     * @param label  node label
     * @param nodeId node ID
     * @throws eu.exeris.kernel.spi.exceptions.graph.GraphQueryException ({@code EX-GRPH-5002})
     *         if a registered edge table naming {@code label} is not a valid SQL identifier;
     *         no statement is prepared. A registered table missing in the current scope fails
     *         the statement with the persistence engine's translated error, and nothing is
     *         deleted.
     */
    @Override
    public void deleteNode(String label, UUID nodeId) {
        Objects.requireNonNull(label, "label must not be null");
        Objects.requireNonNull(nodeId, "nodeId must not be null");
        String sql = buildDeleteNodeSql(label);
        try (PersistenceConnection conn = acquireConnection();
             PersistenceStatement stmt = conn.prepare(sql)) {
            stmt.bindUuid(0, nodeId);
            stmt.bindString(1, label);
            stmt.executeUpdate();
        }
    }

    /**
     * Finds the row in {@code graph_nodes} labeled {@code 'ROOT'}.
     *
     * @return the root node's ID
     * @throws eu.exeris.kernel.spi.exceptions.graph.GraphQueryException ({@code EX-GRPH-5002})
     *         if no row is labeled {@code 'ROOT'}
     */
    @Override
    public UUID getRootNode() {
        String sql = "SELECT id FROM graph_nodes WHERE label = 'ROOT' LIMIT 1";
        try (PersistenceConnection conn = acquireConnection();
             PersistenceStatement stmt = conn.prepare(sql);
             QueryResult queryResult = stmt.executeQuery()) {
            if (queryResult.next()) {
                return queryResult.row().getUuid(0);
            }
            throw new GraphQueryException(ROOT_NODE_QUERY_TYPE, "Root node not found");
        }
    }

    /**
     * Loads every row of {@code edge}'s backing table into {@code builder}, adding both
     * directions when {@code edge} is bidirectional or its direction is
     * {@link GraphEdgeDescriptor.Direction#BOTH}.
     *
     * @param edge    edge descriptor naming the backing table
     * @param builder accumulator to add edges to
     * @throws eu.exeris.kernel.spi.exceptions.graph.GraphQueryException ({@code EX-GRPH-5002})
     *         if the query fails, or {@code edge}'s table name does not match
     *         {@code [A-Za-z][A-Za-z0-9_]*}
     */
    @Override
    @SuppressWarnings("PMD.AvoidCatchingGenericException")
    public void loadAdjacency(GraphEdgeDescriptor edge, CommunityPathFinder.Builder builder) {
        String sql = """
                SELECT source_id, target_id, COALESCE(weight, 1.0), properties
                FROM %s
                """.formatted(requireSqlIdentifier(edge.tableName()));
        try (PersistenceConnection conn = acquireConnection();
             PersistenceStatement stmt = conn.prepare(sql);
             QueryResult queryResult = stmt.executeQuery()) {
            while (queryResult.next()) {
                UUID sourceId = queryResult.row().getUuid(0);
                UUID targetId = queryResult.row().getUuid(1);
                double weight = queryResult.row().getDouble(2);
                String properties = queryResult.row().getString(3);
                builder.addEdge(sourceId, targetId, weight, properties);
                if (edge.bidirectional() || edge.direction() == GraphEdgeDescriptor.Direction.BOTH) {
                    builder.addEdge(targetId, sourceId, weight, properties);
                }
            }
        } catch (RuntimeException cause) {
            throw new GraphQueryException(edge.edgeType(),
                    "Failed to load adjacency for shortest path", cause);
        }
    }

    private PersistenceConnection acquireConnection() {
        return connectionSupplier.get();
    }

    private String buildInsertEdgeSql(GraphEdgeDescriptor edge) {
        return """
                INSERT INTO %s (source_id, target_id, weight, properties)
                VALUES ($1, $2, $3, $4::jsonb)
                """.formatted(requireSqlIdentifier(edge.tableName()));
    }

    private String buildUpsertEdgeSql(GraphEdgeDescriptor edge) {
        return """
                INSERT INTO %s (source_id, target_id, weight, properties)
                VALUES ($1, $2, $3, $4::jsonb)
                ON CONFLICT (source_id, target_id)
                DO UPDATE SET weight = EXCLUDED.weight, properties = EXCLUDED.properties
                """.formatted(requireSqlIdentifier(edge.tableName()));
    }

    private void executeEdgeDml(String sql, UUID sourceId, UUID targetId,
                                double weight, String properties) {
        try (PersistenceConnection conn = acquireConnection();
             PersistenceStatement stmt = conn.prepare(sql)) {
            stmt.bindUuid(0, sourceId);
            stmt.bindUuid(1, targetId);
            stmt.bindDouble(2, weight);
            stmt.bindString(3, properties != null ? properties : "{}");
            stmt.executeUpdate();
        }
    }

    private static String requireSqlIdentifier(String identifier) {
        if (identifier == null || !SQL_IDENTIFIER_PATTERN.matcher(identifier).matches()) {
            throw new GraphQueryException("SQL_IDENTIFIER",
                    "Invalid SQL identifier: expected [A-Za-z][A-Za-z0-9_]*, got: " + identifier);
        }
        return identifier;
    }
}
