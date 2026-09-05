/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.spi.graph;

import eu.exeris.kernel.spi.graph.model.GraphEdgeDescriptor;
import eu.exeris.kernel.spi.graph.model.GraphNodeDescriptor;

import java.util.List;

/**
 * SPI: Main facade for the Graph subsystem — session factory, dialect access,
 * and metadata registry.
 *
 * <h2>The Wall (SPI Compliance)</h2>
 * <p>This interface has <strong>zero knowledge</strong> of JDBC, Neo4j Bolt, io_uring,
 * Redis, DataSource, or any backend-specific concept. It is a pure orchestration contract.
 *
 * <h2>Lifecycle</h2>
 * <ol>
 *   <li>Created once by {@link GraphProvider#createEngine(GraphConfig)} during bootstrap</li>
 *   <li>Bound to {@code ScopedValue} via {@code KernelProviders.GRAPH_ENGINE}</li>
 *   <li>Subsystems call {@link #openSession()} for each unit-of-work</li>
 *   <li>{@link #close()} releases all engine resources at shutdown</li>
 * </ol>
 *
 * <p><b>Allocation:</b> {@link #openSession()} allocates nothing on either tier; this
 *     interface does not establish when or how the {@link GraphSession} it returns
 *     allocates the buffer that a later traversal call writes into.
 * <p><b>Thread confinement:</b> any thread for {@link #registerNodes(List)},
 *     {@link #registerEdges(List)}, {@link #registeredNodes()} and {@link #registeredEdges()}
 *     (the only surface exercised concurrently by the TCK); {@link #openSession()}
 *     thread-safety under concurrent Virtual Threads is untested and undocumented.
 * <p><b>Ownership:</b> the kernel bootstrapper owns the engine and releases it via
 *     {@link #close()} at shutdown; each {@link GraphSession} is owned by the thread that
 *     opened it via {@link #openSession()}.
 *
 * @implNote Community holds references to connection pools directly; Enterprise instead
 *           holds the memory partition claimed from {@code GlobalMemoryArbiter}. The
 *           Community binding allocates the traversal buffer lazily, inside
 *           {@code streamBfsJson()} via {@code MemoryAllocator.allocateNetwork}, rather
 *           than at session-open time.
 * @since 0.5
 * @see GraphProvider
 * @see GraphSession
 */
public interface GraphEngine extends AutoCloseable {

    /**
     * Opens a new session for a single unit-of-work.
     *
     * @return new graph session
     * @throws IllegalStateException if the engine is not running
     * @apiNote Callers MUST close the returned session via try-with-resources.
     */
    GraphSession openSession();

    /**
     * Returns the dialect used by this engine for query transpilation.
     *
     * @return dialect instance
     */
    GraphDialect dialect();

    /**
     * Registers node metadata discovered during bootstrap or annotation scanning.
     *
     * @param nodes node descriptors to register
     * @apiNote Call during the metadata-discovery phase, before any session is opened.
     */
    void registerNodes(List<GraphNodeDescriptor> nodes);

    /**
     * Registers edge metadata discovered during bootstrap or annotation scanning.
     *
     * @param edges edge descriptors to register
     */
    void registerEdges(List<GraphEdgeDescriptor> edges);

    /**
     * Returns all registered node descriptors.
     *
     * @return immutable list of node descriptors
     */
    List<GraphNodeDescriptor> registeredNodes();

    /**
     * Returns all registered edge descriptors.
     *
     * @return immutable list of edge descriptors
     */
    List<GraphEdgeDescriptor> registeredEdges();

    /**
     * Returns the engine name for diagnostics and JFR events.
     *
     * @return engine name (e.g. "Community/JdbcGraph", "Enterprise/NativeGraph")
     */
    String engineName();

    /**
     * Checks if the engine is ready to serve sessions.
     *
     * @return true if engine is running and healthy
     */
    boolean isRunning();

    /**
     * Shuts down the engine and releases every resource it holds.
     *
     * @implSpec An implementation releases every resource it opened for itself; one that
     *           delegates its database access to a shared connection pool (for example,
     *           {@code PersistenceEngine}) holds no pool of its own to close here.
     * @implNote The Community binding closes its own graph-protocol client when one was
     *           opened for the active dialect, and leaves the shared
     *           {@code PersistenceEngine} pool for that pool's owner to close.
     */
    @Override
    void close();
}

