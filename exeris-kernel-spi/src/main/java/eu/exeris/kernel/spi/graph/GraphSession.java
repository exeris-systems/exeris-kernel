/*
 * Copyright (C) 2025-2026 Exeris. All rights reserved.
 *
 * This code is part of the Exeris Systems.
 * Distributed under the proprietary Exeris Software License.
 * Unauthorized copying or distribution is prohibited.
 */
package eu.exeris.kernel.spi.graph;

import eu.exeris.kernel.spi.graph.model.GraphEdgeDescriptor;
import eu.exeris.kernel.spi.graph.model.GraphTraversal;
import eu.exeris.kernel.spi.graph.model.PathResult;
import eu.exeris.kernel.spi.memory.LoanedBuffer;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * SPI: Represents a session for executing graph operations against a backend.
 *
 * <h2>The Wall (SPI Compliance)</h2>
 * <p>This interface has <strong>zero knowledge</strong> of JDBC, Bolt, io_uring, DataSource,
 * OutputStream, or any protocol-specific concept. Data output uses {@link LoanedBuffer}
 * (off-heap) instead of banned {@code java.io.OutputStream}.
 *
 * <h2>Lifecycle</h2>
 * <p>Sessions are obtained from {@link GraphEngine#openSession()} and MUST be closed
 * via try-with-resources. Each session represents a single unit-of-work.
 *
 * <h2>Thread Safety</h2>
 * <p>Sessions are <strong>not</strong> thread-safe. Each virtual thread should obtain
 * its own session from the engine.
 *
 * <h2>Memory Contract</h2>
 * <ul>
 *   <li><b>Community:</b> Arena-per-session via {@code MemoryAllocator.allocate(AllocationHint)}.
 *       {@code LoanedBuffer} is closed when session closes.</li>
 *   <li><b>Enterprise:</b> Slab checkout from preallocated pool.
 *       Zero dynamic allocation after engine startup.</li>
 * </ul>
 *
 * @since 0.5.0
 */
@SuppressWarnings("PMD.TooManyMethods") // SPI contract: one method per graph operation type (traversal, CRUD, tx)
public interface GraphSession extends AutoCloseable {

    // =========================================================================
    // Traversal Operations
    // =========================================================================

    /**
     * Executes a breadth-first search traversal.
     *
     * @param traversal traversal configuration
     * @return list of node IDs found during traversal
     * @throws eu.exeris.kernel.spi.exceptions.graph.GraphQueryException if query fails
     */
    List<UUID> traverseBreadthFirst(GraphTraversal traversal);

    /**
     * Streams BFS results as JSON into a {@link LoanedBuffer}.
     *
     * <p>This is the zero-copy hot-path for large result sets. The JSON bytes
     * are written directly into the loaned buffer's off-heap segment without
     * intermediate heap allocation.
     *
     * <h2>Memory contract</h2>
     * <ul>
     *   <li><b>Community:</b> Allocates a {@code LoanedBuffer} via
     *       {@code MemoryAllocator.allocate(AllocationHint)}, writes JSON,
     *       caller owns the buffer lifecycle.</li>
     *   <li><b>Enterprise:</b> Checks out a slab from the preallocated graph pool,
     *       writes JSON via raw pointer, zero GC.</li>
     * </ul>
     *
     * @param traversal traversal configuration
     * @return loaned buffer containing UTF-8 JSON bytes; caller MUST close
     * @throws eu.exeris.kernel.spi.exceptions.graph.GraphQueryException if query fails
     */
    LoanedBuffer streamBfsJson(GraphTraversal traversal);

    // =========================================================================
    // Edge CRUD
    // =========================================================================

    /**
     * Creates a new edge in the graph.
     *
     * @param edge       edge descriptor
     * @param sourceId   source node ID
     * @param targetId   target node ID
     * @param weight     edge weight
     * @param properties JSON properties as string (may be {@code null})
     * @throws eu.exeris.kernel.spi.exceptions.graph.GraphQueryException if creation fails
     */
    void createEdge(GraphEdgeDescriptor edge, UUID sourceId, UUID targetId,
                    double weight, String properties);

    /**
     * Updates or inserts an edge (upsert semantics).
     *
     * @param edge       edge descriptor
     * @param sourceId   source node ID
     * @param targetId   target node ID
     * @param weight     edge weight
     * @param properties JSON properties as string (may be {@code null})
     * @throws eu.exeris.kernel.spi.exceptions.graph.GraphQueryException if upsert fails
     */
    void upsertEdge(GraphEdgeDescriptor edge, UUID sourceId, UUID targetId,
                    double weight, String properties);

    /**
     * Deletes an edge from the graph.
     *
     * @param edge     edge descriptor
     * @param sourceId source node ID
     * @param targetId target node ID
     * @throws eu.exeris.kernel.spi.exceptions.graph.GraphQueryException if deletion fails
     */
    void deleteEdge(GraphEdgeDescriptor edge, UUID sourceId, UUID targetId);

    // =========================================================================
    // Node CRUD
    // =========================================================================

    /**
     * Creates or updates a node (upsert semantics).
     *
     * @param label      node label
     * @param nodeId     unique node identifier
     * @param properties node properties
     * @throws eu.exeris.kernel.spi.exceptions.graph.GraphQueryException if upsert fails
     */
    void upsertNode(String label, String nodeId, Map<String, Object> properties);

    /**
     * Deletes a node and all connected edges.
     *
     * @param label  node label
     * @param nodeId node identifier
     * @throws eu.exeris.kernel.spi.exceptions.graph.GraphQueryException if deletion fails
     */
    void deleteNode(String label, String nodeId);

    // =========================================================================
    // Graph Root
    // =========================================================================

    /**
     * Returns the root node of the graph (entry point for traversals).
     *
     * @return UUID of the root node
     * @throws eu.exeris.kernel.spi.exceptions.graph.GraphQueryException if root not found
     */
    UUID getRootNode();

    // =========================================================================
    // Algorithm Integration
    // =========================================================================

    /**
     * Finds the shortest path between two nodes.
     *
     * @param source source node ID
     * @param target target node ID
     * @return path result (may indicate not found)
     * @throws eu.exeris.kernel.spi.exceptions.graph.GraphQueryException if algorithm fails
     */
    PathResult findShortestPath(UUID source, UUID target);

    // =========================================================================
    // Transaction Management
    // =========================================================================

    /**
     * Begins a transaction (if supported by backend). May be a no-op.
     */
    void beginTransaction();

    /**
     * Commits the current transaction.
     */
    void commit();

    /**
     * Rolls back the current transaction.
     */
    void rollback();

    /**
     * Closes the session and releases all associated resources.
     * Implementations MUST release any checked-out {@link LoanedBuffer} instances.
     */
    @Override
    void close();
}


