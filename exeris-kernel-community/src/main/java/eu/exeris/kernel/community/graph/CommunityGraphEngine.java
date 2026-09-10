/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
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
 * Community-tier {@link GraphEngine}: delegates both graph storage and buffer allocation to
 * other Community providers rather than holding native resources itself.
 *
 * <h2>Memory Model</h2>
 * <p>Each {@link GraphSession}'s backend obtains its buffers from
 * {@code KernelProviders.MEMORY_ALLOCATOR} as a {@code LoanedBuffer}; this engine holds no
 * allocator or buffer reference of its own.
 *
 * <h2>Backend Delegation</h2>
 * <p>All database access is delegated to {@code KernelProviders.PERSISTENCE_ENGINE},
 * which supplies connections from the Community engine's HikariCP-backed pool. This engine
 * holds no {@code DataSource} or connection pool reference of its own.
 *
 * <p><b>Allocation:</b> constructs one {@link CommunityGraphDialect} and, when the resolved
 * backend is Cypher-mode, one {@link CommunityNeo4jClient}, both at construction time;
 * {@link #openSession()} allocates one {@link CommunityGraphSession} per call and nothing
 * else.
 * <p><b>Thread confinement:</b> any thread — the dialect and Neo4j client fields are set
 * once in the constructor and never reassigned, the node/edge registries are
 * {@code CopyOnWriteArrayList}, and {@link #isRunning()} reads a {@code volatile} flag.
 * Sessions returned by {@link #openSession()} are NOT thread-safe — see
 * {@link CommunityGraphSession}.
 * <p><b>Ownership:</b> constructs the {@link CommunityNeo4jClient} (Cypher-mode only) and
 * releases it via {@link #close()}; sessions obtained from {@link #openSession()} are owned
 * by their caller.
 *
 * @since 0.5
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
        this.neo4jClient = dialect.isCypherMode() && CommunityNeo4jClient.isConfigured(config)
                ? new CommunityNeo4jClient(config)
                : null;
        this.nodes = new CopyOnWriteArrayList<>();
        this.edges = new CopyOnWriteArrayList<>();
        this.running = true;
    }

    /**
     * Creates a {@link CommunityGraphSession} backed by the Cypher or SQL/PGQ helper that
     * this engine's dialect selected.
     *
     * @return a new session; never {@code null}
     * @throws IllegalStateException if the engine has already been {@link #close() closed}
     */
    @Override
    public GraphSession openSession() {
        if (!running) {
            throw new IllegalStateException("CommunityGraphEngine is not running");
        }
        return new CommunityGraphSession(dialect, neo4jClient);
    }

    /**
     * Returns the {@link CommunityGraphDialect} this engine was constructed with.
     *
     * @return the dialect instance; never {@code null}
     */
    @Override
    public GraphDialect dialect() {
        return dialect;
    }

    /**
     * Adds {@code nodeDescriptors} to the registry and, when the dialect is in SQL/PGQ mode
     * and a {@code PersistenceEngine} is bound, issues the shared node-table DDL via a
     * connection acquired for this call.
     *
     * @param nodeDescriptors node descriptors to register
     */
    @Override
    public void registerNodes(List<GraphNodeDescriptor> nodeDescriptors) {
        nodes.addAll(nodeDescriptors);
        if (!dialect.isCypherMode() && KernelProviders.PERSISTENCE_ENGINE.isBound()) {
            try (PersistenceConnection conn = KernelProviders.persistenceEngine().openConnection()) {
                conn.executeUpdate(dialect.buildCreateNodeTable());
            }
        }
    }

    /**
     * Adds {@code edgeDescriptors} to the registry and, when the dialect is in SQL/PGQ mode
     * and a {@code PersistenceEngine} is bound, issues one edge-table DDL statement per
     * descriptor via a connection acquired for this call.
     *
     * @param edgeDescriptors edge descriptors to register
     */
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

    /**
     * Returns a snapshot copy of the registered node descriptors.
     *
     * @return an immutable copy of the registry at call time; does not reflect registrations
     *         added after this call returns
     */
    @Override
    public List<GraphNodeDescriptor> registeredNodes() {
        return List.copyOf(nodes);
    }

    /**
     * Returns a snapshot copy of the registered edge descriptors.
     *
     * @return an immutable copy of the registry at call time; does not reflect registrations
     *         added after this call returns
     */
    @Override
    public List<GraphEdgeDescriptor> registeredEdges() {
        return List.copyOf(edges);
    }

    /**
     * Returns {@code "Community/JdbcGraph"}.
     *
     * @return this engine's name
     */
    @Override
    public String engineName() {
        return ENGINE_NAME;
    }

    /**
     * Returns whether {@link #close()} has not yet been called.
     *
     * @return {@code true} until {@link #close()} runs, {@code false} afterward
     */
    @Override
    public boolean isRunning() {
        return running;
    }

    /**
     * Marks the engine as no longer running and releases the {@link CommunityNeo4jClient},
     * if one was constructed for a Cypher-mode backend.
     *
     * <p>Does not close sessions already obtained from {@link #openSession()} — those
     * remain caller-owned per {@link GraphSession#close()}.
     */
    @Override
    public void close() {
        running = false;
        if (neo4jClient != null) {
            neo4jClient.close();
        }
    }

}



