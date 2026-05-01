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
import eu.exeris.kernel.spi.context.KernelProviders;
import eu.exeris.kernel.spi.exceptions.graph.GraphQueryException;
import eu.exeris.kernel.spi.graph.GraphSession;
import eu.exeris.kernel.spi.graph.algorithm.EdgeWeightFunction;
import eu.exeris.kernel.spi.graph.model.GraphEdgeDescriptor;
import eu.exeris.kernel.spi.graph.model.GraphTraversal;
import eu.exeris.kernel.spi.graph.model.PathResult;
import eu.exeris.kernel.spi.memory.LoanedBuffer;
import eu.exeris.kernel.spi.persistence.PersistenceConnection;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

// SPI contract: one method per graph operation; TooManyMethods are inherent.
@SuppressWarnings("PMD.TooManyMethods")
final class CommunityGraphSession implements GraphSession {
    private static final String ACCESS_QUERY_TYPE = "ACCESS";
    private static final String SHORTEST_PATH_ALGORITHM = "dijkstra";

    private final CommunityGraphBackend backend;

    /* default */ CommunityGraphSession(CommunityGraphDialect dialect, CommunityNeo4jClient neo4jClient) {
        Objects.requireNonNull(dialect, "dialect must not be null");
        this.backend = dialect.isCypherMode()
                ? new CommunityGraphCypherHelper(dialect, neo4jClient)
                : new CommunityGraphSqlHelper(dialect, this::acquireConnection);
    }

    @Override
    public List<UUID> traverseBreadthFirst(GraphTraversal traversal) {
        return backend.traverseBreadthFirst(traversal);
    }

    @Override
    public LoanedBuffer streamBfsJson(GraphTraversal traversal) {
        return backend.streamBfsJson(traversal);
    }

    @Override
    public void createEdge(GraphEdgeDescriptor edge, UUID sourceId, UUID targetId,
                           double weight, String properties) {
        backend.createEdge(edge, sourceId, targetId, weight, properties);
    }

    @Override
    public void upsertEdge(GraphEdgeDescriptor edge, UUID sourceId, UUID targetId,
                           double weight, String properties) {
        backend.upsertEdge(edge, sourceId, targetId, weight, properties);
    }

    @Override
    public void deleteEdge(GraphEdgeDescriptor edge, UUID sourceId, UUID targetId) {
        backend.deleteEdge(edge, sourceId, targetId);
    }

    @Override
    public void upsertNode(String label, UUID nodeId, LoanedBuffer properties) {
        backend.upsertNode(label, nodeId, properties);
    }

    @Override
    public void deleteNode(String label, UUID nodeId) {
        backend.deleteNode(label, nodeId);
    }

    @Override
    public UUID getRootNode() {
        return backend.getRootNode();
    }

    @Override
    public PathResult findShortestPath(UUID source, UUID target) {
        Objects.requireNonNull(source, "source must not be null");
        Objects.requireNonNull(target, "target must not be null");
        if (source.equals(target)) {
            return new PathResult(source, target, List.of(source), 0.0, 0, SHORTEST_PATH_ALGORITHM);
        }
        return PathResult.notFound(source, target, SHORTEST_PATH_ALGORITHM);
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
        backend.loadAdjacency(edge, builder);
        return builder.build().findShortestPath(source, target, EdgeWeightFunction.DEFAULT);
    }

    @Override
    public void beginTransaction() {
        backend.beginTransaction();
    }

    @Override
    public void commit() {
        backend.commit();
    }

    @Override
    public void rollback() {
        backend.rollback();
    }

    @Override
    public void close() {
        backend.close();
    }

    private PersistenceConnection acquireConnection() {
        PersistenceSessionBox box = CommunityHttpRequestProcessor.REQUEST_SESSION.isBound()
                ? CommunityHttpRequestProcessor.REQUEST_SESSION.get()
                : null;
        if (box != null) {
            PersistenceConnection connection = box.requestScopedConnection();
            if (connection != null) {
                return connection;
            }
        }
        if (KernelProviders.PERSISTENCE_ENGINE.isBound()) {
            return KernelProviders.PERSISTENCE_ENGINE.get()
                    .openConnection(KernelProviders.storageContextOrSystem());
        }
        throw new GraphQueryException(ACCESS_QUERY_TYPE,
                "PersistenceEngine is not available in this request scope (no ScopedValue binding)");
    }
}
