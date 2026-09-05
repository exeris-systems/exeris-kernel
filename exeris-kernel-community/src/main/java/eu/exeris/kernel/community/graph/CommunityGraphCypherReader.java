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
import org.neo4j.driver.Value;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Read-only side of the Cypher backend (QA-008 decomposition). Encapsulates BFS,
 * shortest-path adjacency loading, and root-node discovery; mutations live in
 * {@link CommunityGraphCypherWriter}. Lifecycle and session state are owned by the
 * caller via {@link CypherExecutor}.
 *
 * @since 0.7
 */
final class CommunityGraphCypherReader {

    /* default */ static final String CYPHER_EXECUTION_DETAIL = "Failed to execute Cypher operation";
    /* default */ static final String ROOT_NODE_QUERY_TYPE = "ROOT_NODE";
    /* default */ static final String PARAM_SOURCE_ID = "sourceId";
    /* default */ static final String PARAM_PROPERTIES = "properties";

    private static final String PMD_AVOID_CATCHING_GENERIC_EXCEPTION = "PMD.AvoidCatchingGenericException";

    private final CommunityGraphDialect dialect;
    private final CypherExecutor executor;

    /* default */ CommunityGraphCypherReader(CommunityGraphDialect dialect, CypherExecutor executor) {
        this.dialect = dialect;
        this.executor = executor;
    }

    // Neo4j driver raises RuntimeException; map to GraphQueryException
    @SuppressWarnings(PMD_AVOID_CATCHING_GENERIC_EXCEPTION)
    /* default */ List<UUID> traverseBreadthFirst(GraphTraversal traversal) {
        String query = dialect.buildMultiHopQuery(traversal.edgeDescriptor(), 1, traversal.maxDepth());
        Map<String, Object> params = Map.of(PARAM_SOURCE_ID, traversal.startNodeId().toString());
        try {
            return executor.executeRead(query, params, result -> {
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

    /* default */ LoanedBuffer streamBfsJson(GraphTraversal traversal) {
        List<UUID> bfs = traverseBreadthFirst(traversal);
        byte[] jsonBytes = CommunityGraphBufferOps.toUuidJsonArray(bfs);
        LoanedBuffer buffer = KernelProviders.allocator().allocateNetwork(jsonBytes.length);
        MemorySegment.copy(jsonBytes, 0, buffer.segment(), ValueLayout.JAVA_BYTE, 0, jsonBytes.length);
        buffer.setSize(jsonBytes.length);
        return buffer;
    }

    // Neo4j driver raises RuntimeException; map to GraphQueryException
    @SuppressWarnings(PMD_AVOID_CATCHING_GENERIC_EXCEPTION)
    /* default */ UUID getRootNode() {
        String query = "MATCH (n:ROOT) RETURN n.id AS id LIMIT 1";
        try {
            return executor.executeRead(query, Map.of(), result -> {
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

    // Neo4j driver raises RuntimeException; map to GraphQueryException
    @SuppressWarnings(PMD_AVOID_CATCHING_GENERIC_EXCEPTION)
    /* default */ void loadAdjacency(GraphEdgeDescriptor edge, CommunityPathFinder.Builder builder) {
        String sourceLabel = CypherIdentifiers.requireIdentifier(edge.sourceNode());
        String relationType = CypherIdentifiers.requireIdentifier(edge.edgeType());
        String targetLabel = CypherIdentifiers.requireIdentifier(edge.targetNode());
        String query = """
                MATCH (source:%s)-[r:%s]->(target:%s)
                RETURN source.id AS source_id,
                       target.id AS target_id,
                       coalesce(r.weight, 1.0) AS weight,
                       r.%s AS %s
                """.formatted(sourceLabel, relationType, targetLabel,
                PARAM_PROPERTIES, PARAM_PROPERTIES);

        try {
            executor.executeRead(query, Map.of(), result -> {
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

    // Neo4j driver raises RuntimeException; fall back to toString on serialization failure
    @SuppressWarnings(PMD_AVOID_CATCHING_GENERIC_EXCEPTION)
    /* default */ static String toPropertyString(Value value) {
        if (value == null || value.isNull()) {
            return "{}";
        }
        try {
            return value.asString();
        } catch (RuntimeException _) {
            return value.toString();
        }
    }

    // Neo4j driver raises RuntimeException; fall back to asObject on type mismatch
    @SuppressWarnings(PMD_AVOID_CATCHING_GENERIC_EXCEPTION)
    /* default */ static UUID asUuid(Value value, String queryType) {
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
}
