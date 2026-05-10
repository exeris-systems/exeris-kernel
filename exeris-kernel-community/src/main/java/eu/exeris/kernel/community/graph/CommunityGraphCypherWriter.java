/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
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
 * @since 0.7.0
 */
final class CommunityGraphCypherWriter {

    private static final String PARAM_SOURCE_ID = "sourceId";
    private static final String PARAM_TARGET_ID = "targetId";
    private static final String PARAM_PROPERTIES = "properties";

    private final CypherExecutor executor;

    /* default */ CommunityGraphCypherWriter(CypherExecutor executor) {
        this.executor = executor;
    }

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
