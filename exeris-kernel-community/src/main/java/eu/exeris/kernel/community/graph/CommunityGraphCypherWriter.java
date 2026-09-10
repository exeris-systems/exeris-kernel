/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.graph;

import eu.exeris.kernel.spi.graph.model.GraphEdgeDescriptor;
import eu.exeris.kernel.spi.memory.LoanedBuffer;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Mutating side of the Cypher backend (QA-008 decomposition). Encapsulates edge and node
 * upserts/deletes; reads live in {@link CommunityGraphCypherReader}. Lifecycle and
 * session state are owned by the caller via {@link CypherExecutor}.
 *
 * @since 0.7
 */
final class CommunityGraphCypherWriter {

    private static final String PARAM_SOURCE_ID = "sourceId";
    private static final String PARAM_TARGET_ID = "targetId";
    private static final String PARAM_PROPERTIES = "properties";

    private final CypherExecutor executor;

    /* default */ CommunityGraphCypherWriter(CypherExecutor executor) {
        this.executor = executor;
    }

    /**
     * Creates a new edge with a {@code MERGE}d source and target node and a {@code CREATE}d
     * relationship — a second call with the same arguments creates a second, parallel
     * relationship rather than updating the first.
     *
     * @param edge       edge descriptor naming the source label, relationship type, and
     *                   target label
     * @param sourceId   source node ID
     * @param targetId   target node ID
     * @param weight     edge weight, stored as the relationship's {@code weight} property
     * @param properties JSON properties string; {@code null} is stored as {@code "{}"}
     * @throws eu.exeris.kernel.spi.exceptions.graph.GraphQueryException ({@code EX-GRPH-5002})
     *         if the write fails, or {@code edge}'s source node, relation type, or target
     *         node identifier does not match {@code [A-Za-z][A-Za-z0-9_]*}
     */
    /* default */ void createEdge(GraphEdgeDescriptor edge, UUID sourceId, UUID targetId,
                    double weight, String properties) {
        String sourceLabel = CypherIdentifiers.requireIdentifier(edge.sourceNode());
        String relationType = CypherIdentifiers.requireIdentifier(edge.edgeType());
        String targetLabel = CypherIdentifiers.requireIdentifier(edge.targetNode());
        String query = """
                MERGE (source:%s {id: $sourceId})
                MERGE (target:%s {id: $targetId})
                CREATE (source)-[r:%s]->(target)
                SET r.weight = $weight,
                    r.%s = $%s
                """.formatted(sourceLabel, targetLabel, relationType,
                PARAM_PROPERTIES, PARAM_PROPERTIES);
        executor.executeWrite(query, edgeParameters(sourceId, targetId, weight, properties));
    }

    /**
     * Creates or updates an edge: {@code MERGE}s the source node, target node, and the
     * relationship itself, so a repeated call with the same source/target/type updates the
     * existing relationship's {@code weight} and properties instead of creating a parallel
     * one — unlike {@link #createEdge}.
     *
     * @param edge       edge descriptor naming the source label, relationship type, and
     *                   target label
     * @param sourceId   source node ID
     * @param targetId   target node ID
     * @param weight     edge weight, stored as the relationship's {@code weight} property
     * @param properties JSON properties string; {@code null} is stored as {@code "{}"}
     * @throws eu.exeris.kernel.spi.exceptions.graph.GraphQueryException ({@code EX-GRPH-5002})
     *         if the write fails, or {@code edge}'s source node, relation type, or target
     *         node identifier does not match {@code [A-Za-z][A-Za-z0-9_]*}
     */
    /* default */ void upsertEdge(GraphEdgeDescriptor edge, UUID sourceId, UUID targetId,
                    double weight, String properties) {
        String sourceLabel = CypherIdentifiers.requireIdentifier(edge.sourceNode());
        String relationType = CypherIdentifiers.requireIdentifier(edge.edgeType());
        String targetLabel = CypherIdentifiers.requireIdentifier(edge.targetNode());
        String query = """
                MERGE (source:%s {id: $sourceId})
                MERGE (target:%s {id: $targetId})
                MERGE (source)-[r:%s]->(target)
                SET r.weight = $weight,
                    r.%s = $%s
                """.formatted(sourceLabel, targetLabel, relationType,
                PARAM_PROPERTIES, PARAM_PROPERTIES);
        executor.executeWrite(query, edgeParameters(sourceId, targetId, weight, properties));
    }

    /**
     * Deletes the relationship of {@code edge}'s type between {@code sourceId} and
     * {@code targetId}. A no-op if no such relationship exists; both endpoint nodes are left
     * in place.
     *
     * @param edge     edge descriptor naming the source label, relationship type, and target
     *                 label
     * @param sourceId source node ID
     * @param targetId target node ID
     * @throws eu.exeris.kernel.spi.exceptions.graph.GraphQueryException ({@code EX-GRPH-5002})
     *         if the write fails, or {@code edge}'s source node, relation type, or target
     *         node identifier does not match {@code [A-Za-z][A-Za-z0-9_]*}
     */
    /* default */ void deleteEdge(GraphEdgeDescriptor edge, UUID sourceId, UUID targetId) {
        String sourceLabel = CypherIdentifiers.requireIdentifier(edge.sourceNode());
        String relationType = CypherIdentifiers.requireIdentifier(edge.edgeType());
        String targetLabel = CypherIdentifiers.requireIdentifier(edge.targetNode());
        String query = """
                MATCH (source:%s {id: $sourceId})-[r:%s]->(target:%s {id: $targetId})
                DELETE r
                """.formatted(sourceLabel, relationType, targetLabel);
        executor.executeWrite(query, Map.of(
                PARAM_SOURCE_ID, sourceId.toString(),
                PARAM_TARGET_ID, targetId.toString()
        ));
    }

    /**
     * Creates or updates a node: {@code MERGE}s on {@code label} and {@code nodeId}, then
     * sets its properties.
     *
     * @param label      node label
     * @param nodeId     node ID
     * @param properties encoded properties, decoded via
     *                   {@link CommunityGraphBufferOps#decodeProperties}; read but not
     *                   closed — the caller retains ownership. {@code null} is stored as
     *                   {@code "{}"}
     * @throws eu.exeris.kernel.spi.exceptions.graph.GraphQueryException ({@code EX-GRPH-5002})
     *         if the write fails, or {@code label} does not match
     *         {@code [A-Za-z][A-Za-z0-9_]*}
     */
    /* default */ void upsertNode(String label, UUID nodeId, LoanedBuffer properties) {
        String nodeLabel = CypherIdentifiers.requireIdentifier(label);
        String query = """
                MERGE (n:%s {id: $nodeId})
                SET n.%s = $%s
                """.formatted(nodeLabel, PARAM_PROPERTIES, PARAM_PROPERTIES);
        executor.executeWrite(query, Map.of(
                "nodeId", nodeId.toString(),
                PARAM_PROPERTIES, CommunityGraphBufferOps.decodeProperties(properties)
        ));
    }

    /**
     * Deletes the node identified by {@code label} and {@code nodeId}, together with every
     * relationship attached to it ({@code DETACH DELETE}). A no-op if no such node exists.
     *
     * @param label  node label
     * @param nodeId node ID
     * @throws eu.exeris.kernel.spi.exceptions.graph.GraphQueryException ({@code EX-GRPH-5002})
     *         if the write fails, or {@code label} does not match
     *         {@code [A-Za-z][A-Za-z0-9_]*}
     */
    /* default */ void deleteNode(String label, UUID nodeId) {
        String nodeLabel = CypherIdentifiers.requireIdentifier(label);
        String query = """
                MATCH (n:%s {id: $nodeId})
                DETACH DELETE n
                """.formatted(nodeLabel);
        executor.executeWrite(query, Map.of("nodeId", nodeId.toString()));
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
}
