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
import eu.exeris.kernel.spi.graph.model.GraphTraversal;
import eu.exeris.kernel.spi.memory.LoanedBuffer;

import java.util.List;
import java.util.UUID;

// Internal contract mirrors the GraphSession surface to keep the facade branch-free.
@SuppressWarnings("PMD.TooManyMethods")
interface CommunityGraphBackend extends AutoCloseable {
    List<UUID> traverseBreadthFirst(GraphTraversal traversal);

    LoanedBuffer streamBfsJson(GraphTraversal traversal);

    void createEdge(GraphEdgeDescriptor edge, UUID sourceId, UUID targetId,
                    double weight, String properties);

    void upsertEdge(GraphEdgeDescriptor edge, UUID sourceId, UUID targetId,
                    double weight, String properties);

    void deleteEdge(GraphEdgeDescriptor edge, UUID sourceId, UUID targetId);

    void upsertNode(String label, UUID nodeId, LoanedBuffer properties);

    void deleteNode(String label, UUID nodeId);

    UUID getRootNode();

    void loadAdjacency(GraphEdgeDescriptor edge, CommunityPathFinder.Builder builder);

    default void beginTransaction() {
    }

    default void commit() {
    }

    default void rollback() {
    }

    @Override
    default void close() {
    }
}
