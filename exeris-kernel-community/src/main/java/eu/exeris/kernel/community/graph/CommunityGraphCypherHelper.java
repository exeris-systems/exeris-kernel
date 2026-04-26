/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.community.graph;

import eu.exeris.kernel.spi.context.KernelProviders;
import eu.exeris.kernel.spi.exceptions.graph.GraphQueryException;
import eu.exeris.kernel.spi.graph.model.GraphEdgeDescriptor;
import eu.exeris.kernel.spi.graph.model.GraphTraversal;
import eu.exeris.kernel.spi.memory.LoanedBuffer;
import org.neo4j.driver.Result;
import org.neo4j.driver.Session;
import org.neo4j.driver.Transaction;
import org.neo4j.driver.Value;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import java.util.regex.Pattern;

// Backend adapter intentionally mirrors the GraphSession surface for the Cypher dialect.
// GodClass: adapter must implement the complete CommunityGraphBackend interface — all Cypher
// read/write operations share one session boundary; extraction would break transactional cohesion.
@SuppressWarnings({"PMD.TooManyMethods", "PMD.CyclomaticComplexity", "PMD.GodClass"})
final class CommunityGraphCypherHelper implements CommunityGraphBackend {
    private static final String PMD_AVOID_CATCHING_GENERIC_EXCEPTION = "PMD.AvoidCatchingGenericException";
    private static final String CYPHER_BACKEND_QUERY_TYPE = "CYPHER_BACKEND";
    private static final String CYPHER_IDENTIFIER_QUERY_TYPE = "CYPHER_IDENTIFIER";
    private static final String CYPHER_EXECUTION_DETAIL = "Failed to execute Cypher operation";
    private static final String ROOT_NODE_QUERY_TYPE = "ROOT_NODE";
    private static final String PARAM_SOURCE_ID = "sourceId";
    private static final String PARAM_TARGET_ID = "targetId";
    private static final String PARAM_PROPERTIES = "properties";
    private static final Pattern CYPHER_IDENTIFIER_PATTERN = Pattern.compile("^[A-Za-z]\\w*$");

    private final CommunityGraphDialect dialect;
    private final CommunityNeo4jClient neo4jClient;
    private Session cypherSession;
    private Transaction cypherTransaction;

    /* default */ CommunityGraphCypherHelper(CommunityGraphDialect dialect, CommunityNeo4jClient neo4jClient) {
        this.dialect = Objects.requireNonNull(dialect, "dialect must not be null");
        this.neo4jClient = neo4jClient;
    }

    @Override
    @SuppressWarnings(PMD_AVOID_CATCHING_GENERIC_EXCEPTION)
    public List<UUID> traverseBreadthFirst(GraphTraversal traversal) {
        String query = dialect.buildMultiHopQuery(traversal.edgeDescriptor(), 1, traversal.maxDepth());
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

    @Override
    public LoanedBuffer streamBfsJson(GraphTraversal traversal) {
        List<UUID> bfs = traverseBreadthFirst(traversal);
        byte[] jsonBytes = CommunityGraphBufferOps.toUuidJsonArray(bfs);
        LoanedBuffer buffer = KernelProviders.allocator().allocateNetwork(jsonBytes.length);
        MemorySegment.copy(jsonBytes, 0, buffer.segment(), ValueLayout.JAVA_BYTE, 0, jsonBytes.length);
        buffer.setSize(jsonBytes.length);
        return buffer;
    }

    @Override
    public void createEdge(GraphEdgeDescriptor edge, UUID sourceId, UUID targetId,
                    double weight, String properties) {
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
        executeCypherWrite(query, edgeParameters(sourceId, targetId, weight, properties));
    }

    @Override
    public void upsertEdge(GraphEdgeDescriptor edge, UUID sourceId, UUID targetId,
                    double weight, String properties) {
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
        executeCypherWrite(query, edgeParameters(sourceId, targetId, weight, properties));
    }

    @Override
    public void deleteEdge(GraphEdgeDescriptor edge, UUID sourceId, UUID targetId) {
        String sourceLabel = requireIdentifier(edge.sourceNode());
        String relationType = requireIdentifier(edge.edgeType());
        String targetLabel = requireIdentifier(edge.targetNode());
        String query = """
                MATCH (source:%s {id: $sourceId})-[r:%s]->(target:%s {id: $targetId})
                DELETE r
                """.formatted(sourceLabel, relationType, targetLabel);
        executeCypherWrite(query, Map.of(
                PARAM_SOURCE_ID, sourceId.toString(),
                PARAM_TARGET_ID, targetId.toString()
        ));
    }

    @Override
    public void upsertNode(String label, UUID nodeId, LoanedBuffer properties) {
        String nodeLabel = requireIdentifier(label);
        String query = """
                MERGE (n:%s {id: $nodeId})
                SET n.%s = $%s
                """.formatted(nodeLabel, PARAM_PROPERTIES, PARAM_PROPERTIES);
        executeCypherWrite(query, Map.of(
                "nodeId", nodeId.toString(),
            PARAM_PROPERTIES, CommunityGraphBufferOps.decodeProperties(properties)
        ));
    }

    @Override
    public void deleteNode(String label, UUID nodeId) {
        String nodeLabel = requireIdentifier(label);
        String query = """
                MATCH (n:%s {id: $nodeId})
                DETACH DELETE n
                """.formatted(nodeLabel);
        executeCypherWrite(query, Map.of("nodeId", nodeId.toString()));
    }

    @Override
    @SuppressWarnings(PMD_AVOID_CATCHING_GENERIC_EXCEPTION)
    public UUID getRootNode() {
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

    @Override
    @SuppressWarnings(PMD_AVOID_CATCHING_GENERIC_EXCEPTION)
    public void loadAdjacency(GraphEdgeDescriptor edge, CommunityPathFinder.Builder builder) {
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

    @Override
    @SuppressWarnings({PMD_AVOID_CATCHING_GENERIC_EXCEPTION, "PMD.NullAssignment"})
    public void beginTransaction() {
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
    @SuppressWarnings(PMD_AVOID_CATCHING_GENERIC_EXCEPTION)
    public void commit() {
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
    @SuppressWarnings(PMD_AVOID_CATCHING_GENERIC_EXCEPTION)
    public void rollback() {
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
    @SuppressWarnings({PMD_AVOID_CATCHING_GENERIC_EXCEPTION, "PMD.NullAssignment"})
    public void close() {
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

    private Session openCypherSession() {
        return requireNeo4jClient().openSession();
    }

    private CommunityNeo4jClient requireNeo4jClient() {
        if (neo4jClient == null) {
            throw new GraphQueryException(CYPHER_BACKEND_QUERY_TYPE,
                    "Neo4j client is not configured for Cypher backend");
        }
        return neo4jClient;
    }

    private <T> T executeCypherRead(String query, Map<String, Object> params, Function<Result, T> reader) {
        if (cypherTransaction != null) {
            return reader.apply(cypherTransaction.run(query, params));
        }
        try (Session session = openCypherSession()) {
            return reader.apply(session.run(query, params));
        }
    }

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

    private static String requireIdentifier(String identifier) {
        if (identifier == null || !CYPHER_IDENTIFIER_PATTERN.matcher(identifier).matches()) {
            throw new GraphQueryException(CYPHER_IDENTIFIER_QUERY_TYPE,
                    "Invalid Cypher identifier: expected [A-Za-z][A-Za-z0-9_]*");
        }
        return identifier;
    }

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
}
