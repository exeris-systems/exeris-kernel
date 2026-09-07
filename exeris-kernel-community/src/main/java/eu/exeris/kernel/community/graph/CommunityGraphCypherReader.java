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

    /**
     * Runs a multi-hop MATCH from {@code traversal}'s start node out to its max depth and
     * collects the visited node IDs.
     *
     * @param traversal traversal configuration
     * @return node IDs visited, in result order
     * @throws eu.exeris.kernel.spi.exceptions.graph.GraphQueryException ({@code EX-GRPH-5002})
     *         if the underlying Cypher query fails
     */
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

    /**
     * Runs {@link #traverseBreadthFirst} and encodes the resulting node IDs as a UTF-8 JSON
     * array (via {@link CommunityGraphBufferOps#toUuidJsonArray}) into a network buffer from
     * {@code KernelProviders.MEMORY_ALLOCATOR}.
     *
     * @param traversal traversal configuration
     * @return a buffer holding the encoded JSON; caller-owned, caller must close it
     * @throws eu.exeris.kernel.spi.exceptions.graph.GraphQueryException ({@code EX-GRPH-5002})
     *         if the underlying Cypher query fails
     */
    /* default */ LoanedBuffer streamBfsJson(GraphTraversal traversal) {
        List<UUID> bfs = traverseBreadthFirst(traversal);
        byte[] jsonBytes = CommunityGraphBufferOps.toUuidJsonArray(bfs);
        LoanedBuffer buffer = KernelProviders.allocator().allocateNetwork(jsonBytes.length);
        MemorySegment.copy(jsonBytes, 0, buffer.segment(), ValueLayout.JAVA_BYTE, 0, jsonBytes.length);
        buffer.setSize(jsonBytes.length);
        return buffer;
    }

    /**
     * Finds the node labeled {@code ROOT}.
     *
     * @return the root node's ID
     * @throws eu.exeris.kernel.spi.exceptions.graph.GraphQueryException ({@code EX-GRPH-5002})
     *         if no node is labeled {@code ROOT}, or the underlying Cypher query fails
     */
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

    /**
     * Loads every edge of {@code edge}'s relationship type into {@code builder}, adding both
     * directions when {@code edge} is bidirectional or its direction is
     * {@link GraphEdgeDescriptor.Direction#BOTH}.
     *
     * @param edge    relationship type to load adjacency for
     * @param builder accumulator to add edges to
     * @throws eu.exeris.kernel.spi.exceptions.graph.GraphQueryException ({@code EX-GRPH-5002})
     *         if the underlying Cypher query fails, or {@code edge}'s source node, relation
     *         type, or target node identifier does not match {@code [A-Za-z][A-Za-z0-9_]*}
     */
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

    /**
     * Converts a Cypher {@code Value} holding node/edge properties to its string form.
     *
     * @param value the driver value, or {@code null}
     * @return {@code "{}"} when {@code value} is {@code null} or Cypher {@code NULL};
     *         {@code value.asString()} when that conversion succeeds; otherwise
     *         {@code value.toString()}
     */
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

    /**
     * Parses a Cypher {@code Value} as a {@link UUID}.
     *
     * @param value     the driver value returned for an ID column
     * @param queryType query-type label attached to a thrown {@code GraphQueryException}
     * @return the parsed UUID
     * @throws eu.exeris.kernel.spi.exceptions.graph.GraphQueryException ({@code EX-GRPH-5002})
     *         if {@code value} is {@code null}/Cypher {@code NULL}, or its string form
     *         (via {@code asString()}, falling back to {@code asObject()} on type mismatch)
     *         is not a valid UUID
     */
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
