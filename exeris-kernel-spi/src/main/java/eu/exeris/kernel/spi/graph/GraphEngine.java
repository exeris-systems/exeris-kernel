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
 * <h2>Memory Contract</h2>
 * <ul>
 *   <li><b>Community:</b> Engine holds references to connection pools.
 *       Sessions allocate {@code LoanedBuffer} via {@code MemoryAllocator} (Arena per request).</li>
 *   <li><b>Enterprise:</b> Engine claims a memory partition from
 *       {@code GlobalMemoryArbiter} at startup. Sessions check out slabs from
 *       preallocated pools — zero dynamic allocation after {@code createEngine()}.</li>
 * </ul>
 *
 * <h2>Thread Safety</h2>
 * <p>The engine itself is <strong>thread-safe</strong> and shared across all Virtual Threads.
 * Individual {@link GraphSession} instances are <strong>NOT thread-safe</strong> — one
 * session per Virtual Thread, always used within a try-with-resources block.
 *
 * @since 0.5
 * @see GraphProvider
 * @see GraphSession
 */
public interface GraphEngine extends AutoCloseable {

    /**
     * Creates a new session for executing graph operations.
     *
     * <p>Each session represents a single unit-of-work and MUST be closed
     * via try-with-resources.
     *
     * @return new graph session
     * @throws IllegalStateException if engine is not running
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
     * <p>This method is called during the metadata-discovery phase, before
     * any sessions are opened.
     *
     * @param nodes node descriptors to register
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
     * Shuts down the engine and releases all resources.
     *
     * <h2>Memory contract</h2>
     * <ul>
     *   <li><b>Community:</b> Closes JDBC/Bolt connection pools, releases Arenas.</li>
     *   <li><b>Enterprise:</b> Returns memory partition to {@code GlobalMemoryArbiter},
     *       destroys slab pools.</li>
     * </ul>
     */
    @Override
    void close();
}

