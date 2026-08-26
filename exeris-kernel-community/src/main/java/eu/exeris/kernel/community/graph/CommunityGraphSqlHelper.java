/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.graph;

import eu.exeris.kernel.spi.context.KernelProviders;
import eu.exeris.kernel.spi.exceptions.graph.GraphQueryException;
import eu.exeris.kernel.spi.graph.model.GraphEdgeDescriptor;
import eu.exeris.kernel.spi.graph.model.GraphTraversal;
import eu.exeris.kernel.spi.memory.LoanedBuffer;
import eu.exeris.kernel.spi.persistence.PersistenceConnection;
import eu.exeris.kernel.spi.persistence.PersistenceStatement;
import eu.exeris.kernel.spi.persistence.QueryResult;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.regex.Pattern;

// Backend adapter intentionally mirrors the GraphSession surface for the SQL dialect.
// TooManyMethods + CyclomaticComplexity: SQL/PGQ backend adapter implementing the full
// CommunityGraphBackend surface — one method per graph operation type; complexity is structural
// (SQL query branching).
@SuppressWarnings({"PMD.TooManyMethods", "PMD.CyclomaticComplexity"})
final class CommunityGraphSqlHelper implements CommunityGraphBackend {
    private static final String ROOT_NODE_QUERY_TYPE = "ROOT_NODE";
    private static final Pattern SQL_IDENTIFIER_PATTERN = Pattern.compile("^[A-Za-z]\\w*$");

    private final CommunityGraphDialect dialect;
    private final Supplier<PersistenceConnection> connectionSupplier;

    /* default */ CommunityGraphSqlHelper(
            CommunityGraphDialect dialect,
            Supplier<PersistenceConnection> connectionSupplier) {
        this.dialect = Objects.requireNonNull(dialect, "dialect must not be null");
        this.connectionSupplier = Objects.requireNonNull(
                connectionSupplier,
                "connectionSupplier must not be null");
    }

    @Override
    public List<UUID> traverseBreadthFirst(GraphTraversal traversal) {
        String sql = traversal.maxDepth() == 1
                ? dialect.buildMatchQuery(traversal.edgeDescriptor())
                : dialect.buildMultiHopQuery(traversal.edgeDescriptor(), 1, traversal.maxDepth());
        List<UUID> results = new ArrayList<>();
        try (PersistenceConnection conn = acquireConnection();
             PersistenceStatement stmt = conn.prepare(sql)) {
            stmt.bindUuid(0, traversal.startNodeId());
            try (QueryResult queryResult = stmt.executeQuery()) {
                while (queryResult.next()) {
                    results.add(queryResult.row().getUuid(0));
                }
            }
        }
        return results;
    }

    @Override
    public LoanedBuffer streamBfsJson(GraphTraversal traversal) {
        String sql = buildJsonPushDownQuery(traversal);
        try (PersistenceConnection conn = acquireConnection();
             PersistenceStatement stmt = conn.prepare(sql)) {
            stmt.bindUuid(0, traversal.startNodeId());
            try (QueryResult queryResult = stmt.executeQuery()) {
                byte[] jsonBytes;
                if (queryResult.next()) {
                    String json = queryResult.row().getString(0);
                    jsonBytes = json != null
                            ? json.getBytes(StandardCharsets.UTF_8)
                            : CommunityGraphBufferOps.EMPTY_JSON_ARRAY;
                } else {
                    jsonBytes = CommunityGraphBufferOps.EMPTY_JSON_ARRAY;
                }
                LoanedBuffer buffer = KernelProviders.allocator().allocateNetwork(jsonBytes.length);
                MemorySegment.copy(jsonBytes, 0, buffer.segment(),
                        ValueLayout.JAVA_BYTE, 0, jsonBytes.length);
                buffer.setSize(jsonBytes.length);
                return buffer;
            }
        }
    }

    @Override
    public void createEdge(GraphEdgeDescriptor edge, UUID sourceId, UUID targetId,
                    double weight, String properties) {
        executeEdgeDml(buildInsertEdgeSql(edge), sourceId, targetId, weight, properties);
    }

    @Override
    public void upsertEdge(GraphEdgeDescriptor edge, UUID sourceId, UUID targetId,
                    double weight, String properties) {
        executeEdgeDml(buildUpsertEdgeSql(edge), sourceId, targetId, weight, properties);
    }

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

    @Override
    public void deleteNode(String label, UUID nodeId) {
        String sql = "DELETE FROM graph_nodes WHERE id = $1 AND label = $2";
        try (PersistenceConnection conn = acquireConnection();
             PersistenceStatement stmt = conn.prepare(sql)) {
            stmt.bindUuid(0, nodeId);
            stmt.bindString(1, label);
            stmt.executeUpdate();
        }
    }

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

    private String buildJsonPushDownQuery(GraphTraversal traversal) {
        String table = requireSqlIdentifier(traversal.edgeDescriptor().tableName());
        int maxDepth = traversal.maxDepth();
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
                SELECT json_agg(DISTINCT target_id) AS json_result FROM traversal
                """.formatted(table, table, maxDepth);
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
