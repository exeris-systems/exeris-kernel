/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.community.graph;

import eu.exeris.kernel.community.http.CommunityHttpRequestProcessor;
import eu.exeris.kernel.community.persistence.PersistenceSessionBox;
import eu.exeris.kernel.community.persistence.RequestPersistenceSession;
import eu.exeris.kernel.spi.context.KernelProviders;
import eu.exeris.kernel.spi.exceptions.graph.GraphQueryException;
import eu.exeris.kernel.spi.graph.GraphSession;
import eu.exeris.kernel.spi.graph.algorithm.EdgeWeightFunction;
import eu.exeris.kernel.spi.graph.model.GraphEdgeDescriptor;
import eu.exeris.kernel.spi.graph.model.GraphTraversal;
import eu.exeris.kernel.spi.graph.model.PathResult;
import eu.exeris.kernel.spi.memory.LoanedBuffer;
import eu.exeris.kernel.spi.persistence.PersistenceConnection;
import eu.exeris.kernel.spi.persistence.PersistenceStatement;
import eu.exeris.kernel.spi.persistence.QueryResult;
import eu.exeris.kernel.spi.persistence.TransactionIsolation;
import org.neo4j.driver.Result;
import org.neo4j.driver.Session;
import org.neo4j.driver.Transaction;
import org.neo4j.driver.Value;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import java.util.regex.Pattern;

// SPI contract: one method per graph operation; TooManyMethods/CyclomaticComplexity are inherent.
@SuppressWarnings({
        "PMD.TooManyMethods",
        "PMD.CyclomaticComplexity",
        "PMD.GodClass",
        "PMD.CouplingBetweenObjects",
        "PMD.ExcessiveImports"
})
final class CommunityGraphSession implements GraphSession {
    private static final String PMD_AVOID_CATCHING_GENERIC_EXCEPTION = "PMD.AvoidCatchingGenericException";
    private static final byte[] EMPTY_JSON = "[]".getBytes(StandardCharsets.UTF_8);
    private static final String CYPHER_BACKEND_QUERY_TYPE = "CYPHER_BACKEND";
    private static final String CYPHER_IDENTIFIER_QUERY_TYPE = "CYPHER_IDENTIFIER";
    private static final String CYPHER_EXECUTION_DETAIL = "Failed to execute Cypher operation";
    private static final String ROOT_NODE_QUERY_TYPE = "ROOT_NODE";
    private static final String SHORTEST_PATH_ALGORITHM = "dijkstra";
    private static final String PARAM_SOURCE_ID = "sourceId";
    private static final String PARAM_TARGET_ID = "targetId";
    private static final String PARAM_PROPERTIES = "properties";
    private static final Pattern CYPHER_IDENTIFIER_PATTERN = Pattern.compile("^[A-Za-z]\\w*$");
    private static final Pattern SQL_IDENTIFIER_PATTERN = Pattern.compile("^[A-Za-z][A-Za-z0-9_]*$");

    private final CommunityGraphDialect dialect;
    private final CommunityNeo4jClient neo4jClient;
    private Session cypherSession;
    private Transaction cypherTransaction;

    /* default */ CommunityGraphSession(CommunityGraphDialect dialect, CommunityNeo4jClient neo4jClient) {
        this.dialect = dialect;
        this.neo4jClient = neo4jClient;
    }

    @Override
    public List<UUID> traverseBreadthFirst(GraphTraversal traversal) {
        if (dialect.isCypherMode()) {
            return traverseBreadthFirstCypher(traversal);
        }

        String sql = dialect.buildMultiHopQuery(traversal.edgeDescriptor(), 1, traversal.maxDepth());
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
        if (dialect.isCypherMode()) {
            List<UUID> bfs = traverseBreadthFirstCypher(traversal);
            byte[] jsonBytes = toUuidJsonArray(bfs);
            LoanedBuffer buffer = KernelProviders.allocator().allocateNetwork(jsonBytes.length);
            MemorySegment.copy(jsonBytes, 0, buffer.segment(), ValueLayout.JAVA_BYTE, 0, jsonBytes.length);
            buffer.setSize(jsonBytes.length);
            return buffer;
        }

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
                            : EMPTY_JSON;
                } else {
                    jsonBytes = EMPTY_JSON;
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
        if (dialect.isCypherMode()) {
            String sourceLabel = requireIdentifier(edge.sourceNode());
            String relationType = requireIdentifier(edge.edgeType());
            String targetLabel = requireIdentifier(edge.targetNode());
            String query = """
                    MERGE (source:%s {id: $sourceId})
                    MERGE (target:%s {id: $targetId})
                    CREATE (source)-[r:%s]->(target)
                    SET r.weight = $weight,
                        r.%s = $%s
                    """.formatted(sourceLabel, targetLabel, relationType,
                    PARAM_PROPERTIES, PARAM_PROPERTIES);
            Map<String, Object> params = edgeParameters(sourceId, targetId, weight, properties);
            executeCypherWrite(query, params);
            return;
        }
        executeEdgeDml(buildInsertEdgeSql(edge), sourceId, targetId, weight, properties);
    }

    @Override
    public void upsertEdge(GraphEdgeDescriptor edge, UUID sourceId, UUID targetId,
                           double weight, String properties) {
        if (dialect.isCypherMode()) {
            String sourceLabel = requireIdentifier(edge.sourceNode());
            String relationType = requireIdentifier(edge.edgeType());
            String targetLabel = requireIdentifier(edge.targetNode());
            String query = """
                    MERGE (source:%s {id: $sourceId})
                    MERGE (target:%s {id: $targetId})
                    MERGE (source)-[r:%s]->(target)
                    SET r.weight = $weight,
                        r.%s = $%s
                    """.formatted(sourceLabel, targetLabel, relationType,
                    PARAM_PROPERTIES, PARAM_PROPERTIES);
            Map<String, Object> params = edgeParameters(sourceId, targetId, weight, properties);
            executeCypherWrite(query, params);
            return;
        }
        executeEdgeDml(buildUpsertEdgeSql(edge), sourceId, targetId, weight, properties);
    }

    @Override
    public void deleteEdge(GraphEdgeDescriptor edge, UUID sourceId, UUID targetId) {
        if (dialect.isCypherMode()) {
            String sourceLabel = requireIdentifier(edge.sourceNode());
            String relationType = requireIdentifier(edge.edgeType());
            String targetLabel = requireIdentifier(edge.targetNode());
            String query = """
                    MATCH (source:%s {id: $sourceId})-[r:%s]->(target:%s {id: $targetId})
                    DELETE r
                    """.formatted(sourceLabel, relationType, targetLabel);
            Map<String, Object> params = Map.of(
                    PARAM_SOURCE_ID, sourceId.toString(),
                    PARAM_TARGET_ID, targetId.toString()
            );
            executeCypherWrite(query, params);
            return;
        }

        String sql = "DELETE FROM %s WHERE source_id = ? AND target_id = ?"
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
        if (dialect.isCypherMode()) {
            String nodeLabel = requireIdentifier(label);
            String query = """
                    MERGE (n:%s {id: $nodeId})
                SET n.%s = $%s
                """.formatted(nodeLabel, PARAM_PROPERTIES, PARAM_PROPERTIES);
            Map<String, Object> params = Map.of(
                    "nodeId", nodeId.toString(),
                    PARAM_PROPERTIES, decodeProperties(properties)
            );
            executeCypherWrite(query, params);
            return;
        }

        String sql = """
                INSERT INTO graph_nodes (id, label, properties)
                VALUES (?, ?, ?::jsonb)
                ON CONFLICT (id) DO UPDATE SET properties = EXCLUDED.properties
                """;
        try (PersistenceConnection conn = acquireConnection();
             PersistenceStatement stmt = conn.prepare(sql)) {
            stmt.bindUuid(0, nodeId);
            stmt.bindString(1, label);
            stmt.bindString(2, decodeProperties(properties));
            stmt.executeUpdate();
        }
    }

    @Override
    public void deleteNode(String label, UUID nodeId) {
        if (dialect.isCypherMode()) {
            String nodeLabel = requireIdentifier(label);
            String query = """
                    MATCH (n:%s {id: $nodeId})
                    DETACH DELETE n
                    """.formatted(nodeLabel);
            executeCypherWrite(query, Map.of("nodeId", nodeId.toString()));
            return;
        }

        String sql = "DELETE FROM graph_nodes WHERE id = ? AND label = ?";
        try (PersistenceConnection conn = acquireConnection();
             PersistenceStatement stmt = conn.prepare(sql)) {
            stmt.bindUuid(0, nodeId);
            stmt.bindString(1, label);
            stmt.executeUpdate();
        }
    }

    @Override
    // Normalize backend-specific runtime failures into a stable GraphQueryException contract.
    @SuppressWarnings(PMD_AVOID_CATCHING_GENERIC_EXCEPTION)
    public UUID getRootNode() {
        if (dialect.isCypherMode()) {
            String query = "MATCH (n:ROOT) RETURN n.id AS id LIMIT 1";
            try {
                return executeCypherRead(query, Map.of(), result -> {
                    if (!result.hasNext()) {
                        throw new GraphQueryException(ROOT_NODE_QUERY_TYPE, "Root node not found");
                    }
                    return asUuid(result.next().get("id"), ROOT_NODE_QUERY_TYPE);
                });
            } catch (GraphQueryException cause) {
                throw cause;
            } catch (RuntimeException cause) {
                throw new GraphQueryException(ROOT_NODE_QUERY_TYPE, CYPHER_EXECUTION_DETAIL, cause);
            }
        }

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
    public PathResult findShortestPath(UUID source, UUID target) {
        Objects.requireNonNull(source, "source must not be null");
        Objects.requireNonNull(target, "target must not be null");
        if (source.equals(target)) {
            return new PathResult(source, target, List.of(source), 0.0, 0, SHORTEST_PATH_ALGORITHM);
        }
        throw new GraphQueryException(
                SHORTEST_PATH_ALGORITHM,
                "Shortest path resolution without a GraphEdgeDescriptor is not supported; "
                        + "use findShortestPath(GraphEdgeDescriptor, UUID, UUID)"
        );
    }

    @Override
    public PathResult findShortestPath(GraphEdgeDescriptor edge, UUID source, UUID target) {
        Objects.requireNonNull(edge, "edge must not be null");
        Objects.requireNonNull(source, "source must not be null");
        Objects.requireNonNull(target, "target must not be null");

        if (source.equals(target)) {
            return new PathResult(source, target, List.of(source), 0.0, 0, SHORTEST_PATH_ALGORITHM);
        }

        CommunityPathFinder.Builder builder = CommunityPathFinder.builder();
        if (dialect.isCypherMode()) {
            loadAdjacencyCypher(edge, builder);
        } else {
            loadAdjacencySql(edge, builder);
        }
        return builder.build().findShortestPath(source, target, EdgeWeightFunction.DEFAULT);
    }

    @Override
    // Normalize backend-specific runtime failures into a stable GraphQueryException contract.
    @SuppressWarnings({PMD_AVOID_CATCHING_GENERIC_EXCEPTION, "PMD.NullAssignment"})
    public void beginTransaction() {
        if (!dialect.isCypherMode()) {
            return;
        }
        if (cypherTransaction != null) {
            return;
        }
        try {
            cypherSession = openCypherSession();
            cypherTransaction = cypherSession.beginTransaction();
        } catch (RuntimeException cause) {
            closeSessionQuietly(cypherSession);
            cypherSession = null;
            cypherTransaction = null;
            throw new GraphQueryException(CYPHER_BACKEND_QUERY_TYPE, "Failed to begin Cypher transaction", cause);
        }
    }

    @Override
    // Normalize backend-specific runtime failures into a stable GraphQueryException contract.
    @SuppressWarnings(PMD_AVOID_CATCHING_GENERIC_EXCEPTION)
    public void commit() {
        if (!dialect.isCypherMode()) {
            return;
        }
        if (cypherTransaction == null) {
            return;
        }
        try {
            cypherTransaction.commit();
        } catch (RuntimeException cause) {
            throw new GraphQueryException(CYPHER_BACKEND_QUERY_TYPE, "Failed to commit Cypher transaction", cause);
        } finally {
            closeCypherTransactionResources();
        }
    }

    @Override
    // Normalize backend-specific runtime failures into a stable GraphQueryException contract.
    @SuppressWarnings(PMD_AVOID_CATCHING_GENERIC_EXCEPTION)
    public void rollback() {
        if (!dialect.isCypherMode()) {
            return;
        }
        if (cypherTransaction == null) {
            return;
        }
        try {
            cypherTransaction.rollback();
        } catch (RuntimeException cause) {
            throw new GraphQueryException(CYPHER_BACKEND_QUERY_TYPE, "Failed to rollback Cypher transaction", cause);
        } finally {
            closeCypherTransactionResources();
        }
    }

    @Override
    // Normalize backend-specific runtime failures into a stable GraphQueryException contract.
    @SuppressWarnings({PMD_AVOID_CATCHING_GENERIC_EXCEPTION, "PMD.NullAssignment"})
    public void close() {
        if (!dialect.isCypherMode()) {
            return;
        }
        if (cypherTransaction != null) {
            try {
                cypherTransaction.rollback();
            } catch (RuntimeException cause) {
                throw new GraphQueryException(CYPHER_BACKEND_QUERY_TYPE,
                        "Failed to rollback active Cypher transaction during close", cause);
            } finally {
                closeCypherTransactionResources();
            }
            return;
        }
        if (cypherSession != null) {
            try {
                cypherSession.close();
            } finally {
                cypherSession = null;
            }
        }
    }

    @SuppressWarnings("PMD.LawOfDemeter")
    private PersistenceConnection acquireConnection() {
        requireSqlExecutionBackend();
        PersistenceSessionBox box = CommunityHttpRequestProcessor.REQUEST_SESSION.isBound()
                ? CommunityHttpRequestProcessor.REQUEST_SESSION.get()
                : null;
        if (box != null) {
            RequestPersistenceSession session = box.getOrAcquire();
            if (session != null) {
                return new NonOwningPersistenceConnection(session.connection());
            }
        }
        if (KernelProviders.PERSISTENCE_ENGINE.isBound()) {
            return KernelProviders.PERSISTENCE_ENGINE.get()
                    .openConnection(KernelProviders.storageContextOrSystem());
        }
        throw new GraphQueryException("ACCESS",
                "PersistenceEngine is not available in this request scope (no ScopedValue binding)");
    }

    private void requireSqlExecutionBackend() {
        if (dialect.isCypherMode()) {
            throw new GraphQueryException(CYPHER_BACKEND_QUERY_TYPE, "SQL execution requested in Cypher mode");
        }
    }

    private CommunityNeo4jClient requireNeo4jClient() {
        if (neo4jClient == null) {
            throw new GraphQueryException(CYPHER_BACKEND_QUERY_TYPE,
                    "Neo4j client is not configured for Cypher backend");
        }
        return neo4jClient;
    }

    private Session openCypherSession() {
        return requireNeo4jClient().openSession();
    }

    // Normalize backend-specific runtime failures into a stable GraphQueryException contract.
    @SuppressWarnings(PMD_AVOID_CATCHING_GENERIC_EXCEPTION)
    private List<UUID> traverseBreadthFirstCypher(GraphTraversal traversal) {
        String query = dialect.buildMultiHopQuery(
                traversal.edgeDescriptor(), 1, traversal.maxDepth());
        Map<String, Object> params = Map.of(PARAM_SOURCE_ID, traversal.startNodeId().toString());
        try {
            return executeCypherRead(query, params, result -> {
                List<UUID> ids = new ArrayList<>();
                while (result.hasNext()) {
                    ids.add(asUuid(result.next().get("id"), "BFS"));
                }
                return ids;
            });
        } catch (RuntimeException cause) {
            throw new GraphQueryException("BFS", CYPHER_EXECUTION_DETAIL, cause);
        }
    }

    private <T> T executeCypherRead(String query, Map<String, Object> params,
                                    Function<Result, T> reader) {
        if (cypherTransaction != null) {
            return reader.apply(cypherTransaction.run(query, params));
        }
        try (Session session = openCypherSession()) {
            return reader.apply(session.run(query, params));
        }
    }

    // Normalize backend-specific runtime failures into a stable GraphQueryException contract.
    @SuppressWarnings(PMD_AVOID_CATCHING_GENERIC_EXCEPTION)
    private void executeCypherWrite(String query, Map<String, Object> params) {
        try {
            if (cypherTransaction != null) {
                cypherTransaction.run(query, params).consume();
                return;
            }
            try (Session session = openCypherSession()) {
                session.run(query, params).consume();
            }
        } catch (RuntimeException cause) {
            throw new GraphQueryException(CYPHER_BACKEND_QUERY_TYPE, CYPHER_EXECUTION_DETAIL, cause);
        }
    }

    private static Map<String, Object> edgeParameters(UUID sourceId, UUID targetId,
                                                      double weight, String properties) {
        Map<String, Object> params = new HashMap<>();
        params.put(PARAM_SOURCE_ID, sourceId.toString());
        params.put(PARAM_TARGET_ID, targetId.toString());
        params.put("weight", weight);
        params.put(PARAM_PROPERTIES, properties != null ? properties : "{}");
        return params;
    }

    private static String decodeProperties(LoanedBuffer properties) {
        if (properties != null && properties.size() > 0) {
            byte[] bytes = properties.segment()
                    .asSlice(0, properties.size())
                    .toArray(ValueLayout.JAVA_BYTE);
            return new String(bytes, StandardCharsets.UTF_8);
        }
        return "{}";
    }

    private static byte[] toUuidJsonArray(List<UUID> ids) {
        if (ids.isEmpty()) {
            return EMPTY_JSON;
        }
        StringBuilder builder = new StringBuilder(ids.size() * 40 + 2);
        builder.append('[');
        for (int i = 0; i < ids.size(); i++) {
            if (i > 0) {
                builder.append(',');
            }
            builder.append('"').append(ids.get(i)).append('"');
        }
        builder.append(']');
        return builder.toString().getBytes(StandardCharsets.UTF_8);
    }

    private String buildJsonPushDownQuery(GraphTraversal traversal) {
        String table = requireSqlIdentifier(traversal.edgeDescriptor().tableName());
        int maxDepth = traversal.maxDepth();
        return """
                WITH RECURSIVE traversal AS (
                    SELECT target_id, 1 AS depth
                    FROM %s
                    WHERE source_id = ?
                    UNION ALL
                    SELECT e.target_id, t.depth + 1
                    FROM %s e
                    INNER JOIN traversal t ON e.source_id = t.target_id
                    WHERE t.depth < %d
                )
                SELECT json_agg(DISTINCT target_id) AS json_result FROM traversal
                """.formatted(table, table, maxDepth);
    }

    // Normalize backend-specific runtime failures into a stable GraphQueryException contract.
    @SuppressWarnings(PMD_AVOID_CATCHING_GENERIC_EXCEPTION)
    private void loadAdjacencySql(GraphEdgeDescriptor edge, CommunityPathFinder.Builder builder) {
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

    // Normalize backend-specific runtime failures into a stable GraphQueryException contract.
    @SuppressWarnings(PMD_AVOID_CATCHING_GENERIC_EXCEPTION)
    private void loadAdjacencyCypher(GraphEdgeDescriptor edge, CommunityPathFinder.Builder builder) {
        String sourceLabel = requireIdentifier(edge.sourceNode());
        String relationType = requireIdentifier(edge.edgeType());
        String targetLabel = requireIdentifier(edge.targetNode());
        String query = """
                MATCH (source:%s)-[r:%s]->(target:%s)
                RETURN source.id AS source_id,
                       target.id AS target_id,
                       coalesce(r.weight, 1.0) AS weight,
                  r.%s AS %s
              """.formatted(sourceLabel, relationType, targetLabel,
              PARAM_PROPERTIES, PARAM_PROPERTIES);

        try {
            executeCypherRead(query, Map.of(), result -> {
                while (result.hasNext()) {
                    org.neo4j.driver.Record row = result.next();
                    UUID sourceId = asUuid(row.get("source_id"), edge.edgeType());
                    UUID targetId = asUuid(row.get("target_id"), edge.edgeType());
                    double weight = row.get("weight").asDouble(1.0);
                    String properties = toPropertyString(row.get(PARAM_PROPERTIES));
                    builder.addEdge(sourceId, targetId, weight, properties);
                    if (edge.bidirectional() || edge.direction() == GraphEdgeDescriptor.Direction.BOTH) {
                        builder.addEdge(targetId, sourceId, weight, properties);
                    }
                }
                return null;
            });
        } catch (RuntimeException cause) {
            throw new GraphQueryException(edge.edgeType(),
                    "Failed to load adjacency for shortest path", cause);
        }
    }

    // Normalize backend-specific runtime failures into a stable GraphQueryException contract.
    @SuppressWarnings(PMD_AVOID_CATCHING_GENERIC_EXCEPTION)
    private static String toPropertyString(Value value) {
        if (value == null || value.isNull()) {
            return "{}";
        }
        try {
            return value.asString();
        } catch (RuntimeException _) {
            return value.toString();
        }
    }

    // Normalize backend-specific runtime failures into a stable GraphQueryException contract.
    @SuppressWarnings(PMD_AVOID_CATCHING_GENERIC_EXCEPTION)
    private static UUID asUuid(Value value, String queryType) {
        if (value == null || value.isNull()) {
            throw new GraphQueryException(queryType, "Cypher query returned null UUID");
        }
        String raw;
        try {
            raw = value.asString();
        } catch (RuntimeException _) {
            raw = String.valueOf(value.asObject());
        }
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException cause) {
            throw new GraphQueryException(queryType, "Cypher query returned invalid UUID", cause);
        }
    }

    private String requireIdentifier(String identifier) {
        if (identifier == null || !CYPHER_IDENTIFIER_PATTERN.matcher(identifier).matches()) {
            throw new GraphQueryException(CYPHER_IDENTIFIER_QUERY_TYPE,
                    "Invalid Cypher identifier: expected [A-Za-z][A-Za-z0-9_]*");
        }
        return identifier;
    }

    private static String requireSqlIdentifier(String identifier) {
        if (identifier == null || !SQL_IDENTIFIER_PATTERN.matcher(identifier).matches()) {
            throw new GraphQueryException("SQL_IDENTIFIER",
                    "Invalid SQL identifier: expected [A-Za-z][A-Za-z0-9_]*, got: " + identifier);
        }
        return identifier;
    }

    // Lifecycle reset is intentional after transaction/session teardown.
    @SuppressWarnings("PMD.NullAssignment")
    private void closeCypherTransactionResources() {
        closeTransactionQuietly(cypherTransaction);
        cypherTransaction = null;
        closeSessionQuietly(cypherSession);
        cypherSession = null;
    }

    private static void closeTransactionQuietly(Transaction transaction) {
        if (transaction != null) {
            transaction.close();
        }
    }

    private static void closeSessionQuietly(Session session) {
        if (session != null) {
            session.close();
        }
    }

    private String buildInsertEdgeSql(GraphEdgeDescriptor edge) {
        return """
                INSERT INTO %s (source_id, target_id, weight, properties)
                VALUES (?, ?, ?, ?::jsonb)
                """.formatted(requireSqlIdentifier(edge.tableName()));
    }

    private String buildUpsertEdgeSql(GraphEdgeDescriptor edge) {
        return """
                INSERT INTO %s (source_id, target_id, weight, properties)
                VALUES (?, ?, ?, ?::jsonb)
                ON CONFLICT (source_id, target_id, tenant_id)
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

    private static final class NonOwningPersistenceConnection implements PersistenceConnection {
        private final PersistenceConnection delegate;

        private NonOwningPersistenceConnection(PersistenceConnection delegate) {
            this.delegate = delegate;
        }

        @Override
        public PersistenceStatement prepare(String sql) {
            return delegate.prepare(sql);
        }

        @Override
        public QueryResult executeQuery(String sql) {
            return delegate.executeQuery(sql);
        }

        @Override
        public long executeUpdate(String sql) {
            return delegate.executeUpdate(sql);
        }

        @Override
        public void beginTransaction() {
            delegate.beginTransaction();
        }

        @Override
        public void beginTransaction(TransactionIsolation isolation, boolean readOnly) {
            delegate.beginTransaction(isolation, readOnly);
        }

        @Override
        public void commit() {
            delegate.commit();
        }

        @Override
        public void rollback() {
            delegate.rollback();
        }

        @Override
        public boolean inTransaction() {
            return delegate.inTransaction();
        }

        @Override
        public boolean isOpen() {
            return delegate.isOpen();
        }

        @Override
        public void close() {
            // Request-scoped connection lifecycle is managed by HTTP request session.
        }
    }
}
