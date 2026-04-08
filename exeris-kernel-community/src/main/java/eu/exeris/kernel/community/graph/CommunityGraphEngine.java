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
import eu.exeris.kernel.spi.graph.GraphConfig;
import eu.exeris.kernel.spi.graph.GraphDialect;
import eu.exeris.kernel.spi.graph.GraphEngine;
import eu.exeris.kernel.spi.graph.GraphSession;
import eu.exeris.kernel.spi.graph.model.GraphEdgeDescriptor;
import eu.exeris.kernel.spi.graph.model.GraphNodeDescriptor;
import eu.exeris.kernel.spi.persistence.PersistenceConnection;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Community Graph Engine — heap-based, Arena-per-request.
 *
 * <h2>Memory Model</h2>
 * <p>Each {@link GraphSession} obtains buffers from {@code KernelProviders.MEMORY_ALLOCATOR}
 * via {@code LoanedBuffer} (Arena per request). No preallocated slab pools.
 * Temporary arenas are created per operation and closed via try-with-resources.
 *
 * <h2>Backend Delegation</h2>
 * <p>All database access is delegated to {@code KernelProviders.PERSISTENCE_ENGINE},
 * which provides connections from the shared HikariCP pool. This engine does
 * NOT hold its own DataSource or connection pool reference.
 *
 * <h2>Thread Safety</h2>
 * <p>The engine itself is thread-safe (immutable config + concurrent node/edge lists).
 * Individual sessions are NOT thread-safe — each virtual thread must open its own.
 *
 * @since 0.5.0
 */
public final class CommunityGraphEngine implements GraphEngine {

    private static final String ENGINE_NAME = "Community/JdbcGraph";

    private final CommunityGraphDialect dialect;
    private final CommunityNeo4jClient neo4jClient;
    private final List<GraphNodeDescriptor> nodes;
    private final List<GraphEdgeDescriptor> edges;
    private volatile boolean running;

    /* default */ CommunityGraphEngine(GraphConfig config) {
        this.dialect = new CommunityGraphDialect(config.graphName(), config.backendType());
        this.neo4jClient = dialect.isCypherMode() ? new CommunityNeo4jClient(config) : null;
        this.nodes = new CopyOnWriteArrayList<>();
        this.edges = new CopyOnWriteArrayList<>();
        this.running = true;
    }

    @Override
    public GraphSession openSession() {
        if (!running) {
            throw new IllegalStateException("CommunityGraphEngine is not running");
        }
        return new CommunityGraphSession(dialect, neo4jClient);
    }

    @Override
    public GraphDialect dialect() {
        return dialect;
    }

    @Override
    public void registerNodes(List<GraphNodeDescriptor> nodeDescriptors) {
        nodes.addAll(nodeDescriptors);
        if (!dialect.isCypherMode() && KernelProviders.PERSISTENCE_ENGINE.isBound()) {
            try (PersistenceConnection conn = KernelProviders.persistenceEngine().openConnection()) {
                conn.executeUpdate(dialect.buildCreateNodeTable());
            }
        }
    }

    @Override
    public void registerEdges(List<GraphEdgeDescriptor> edgeDescriptors) {
        edges.addAll(edgeDescriptors);
        if (!dialect.isCypherMode() && KernelProviders.PERSISTENCE_ENGINE.isBound()) {
            try (PersistenceConnection conn = KernelProviders.persistenceEngine().openConnection()) {
                for (GraphEdgeDescriptor edge : edgeDescriptors) {
                    conn.executeUpdate(dialect.buildCreateEdgeTable(edge));
                }
            }
        }
    }

    @Override
    public List<GraphNodeDescriptor> registeredNodes() {
        return List.copyOf(nodes);
    }

    @Override
    public List<GraphEdgeDescriptor> registeredEdges() {
        return List.copyOf(edges);
    }

    @Override
    public String engineName() {
        return ENGINE_NAME;
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public void close() {
        running = false;
        if (neo4jClient != null) {
            neo4jClient.close();
        }
    }

}



