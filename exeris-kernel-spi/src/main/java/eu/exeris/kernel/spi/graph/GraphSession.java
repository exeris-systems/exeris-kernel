/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.spi.graph;

import eu.exeris.kernel.spi.graph.model.GraphEdgeDescriptor;
import eu.exeris.kernel.spi.graph.model.GraphTraversal;
import eu.exeris.kernel.spi.graph.model.PathResult;
import eu.exeris.kernel.spi.memory.LoanedBuffer;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * SPI: A single unit-of-work against the graph backend — traversals, edge and node CRUD,
 * shortest-path queries, and transaction control.
 *
 * <p>A session is obtained from {@link GraphEngine#openSession()} and MUST be closed via
 * try-with-resources when the unit-of-work is done.
 *
 * <h2>The Wall (SPI Compliance)</h2>
 * <p>This interface has <strong>zero knowledge</strong> of JDBC, Bolt, io_uring, DataSource,
 * OutputStream, or any protocol-specific concept. Data output uses {@link LoanedBuffer}
 * (off-heap) instead of banned {@code java.io.OutputStream}.
 *
 * <p><b>Allocation:</b> obtaining a session allocates no buffer; an implementation
 *     allocates the buffer returned by {@link #streamBfsJson(GraphTraversal)} only when
 *     that method is invoked, never at session-open time.
 * <p><b>Thread confinement:</b> owner thread — a session is not thread-safe; each Virtual
 *     Thread obtains its own session from {@link GraphEngine#openSession()} and confines it
 *     to that thread for the session's whole unit-of-work.
 * <p><b>Ownership:</b> the thread that opened the session owns it and MUST close it via
 *     try-with-resources; {@link #close()} releases every resource the session itself
 *     opened, and an implementation whose database access is delegated to a shared
 *     connection pool holds no session-retained resource of its own to release there. A
 *     {@link LoanedBuffer} handed back from {@link #streamBfsJson(GraphTraversal)} passes
 *     to the caller instead — see that method's own ownership contract.
 *
 * @implNote The Community binding backed by the SQL dialect allocates the buffer
 *           returned by {@link #streamBfsJson(GraphTraversal)} via
 *           {@code MemoryAllocator.allocateNetwork(int)} and has no session-retained
 *           resource to release in {@code close()}; the binding backed by the Cypher
 *           dialect closes its Neo4j driver session and transaction there.
 * @since 0.5
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
     * @throws eu.exeris.kernel.spi.exceptions.graph.GraphQueryException {@code (EX-GRPH-5002)}
     *         if the query fails
     */
    List<UUID> traverseBreadthFirst(GraphTraversal traversal);

    /**
     * Streams BFS results as JSON directly into a {@link LoanedBuffer} — the zero-copy hot
     * path for large result sets, writing into the buffer's off-heap segment with no
     * intermediate heap allocation.
     *
     * @param traversal traversal configuration
     * @return a loaned buffer containing UTF-8 JSON bytes; <strong>caller-owned</strong> —
     *         the caller MUST close it
     * @throws eu.exeris.kernel.spi.exceptions.graph.GraphQueryException {@code (EX-GRPH-5002)}
     *         if the query fails
     * @implSpec Implementations MUST transfer ownership of the returned buffer to the caller
     *           and MUST NOT close it themselves. Community allocates it via
     *           {@code MemoryAllocator.allocateNetwork(int)} and writes JSON before
     *           returning it; Enterprise checks out a slab from the preallocated graph pool
     *           and writes JSON through a raw pointer, with zero GC.
     * @apiNote The caller owns the returned buffer and MUST close it, preferably via
     *          try-with-resources; the session will not close it once returned.
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
     * @throws eu.exeris.kernel.spi.exceptions.graph.GraphQueryException {@code (EX-GRPH-5002)}
     *         if creation fails
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
     * @throws eu.exeris.kernel.spi.exceptions.graph.GraphQueryException {@code (EX-GRPH-5002)}
     *         if the upsert fails
     */
    void upsertEdge(GraphEdgeDescriptor edge, UUID sourceId, UUID targetId,
                    double weight, String properties);

    /**
     * Deletes an edge from the graph.
     *
     * @param edge     edge descriptor
     * @param sourceId source node ID
     * @param targetId target node ID
     * @throws eu.exeris.kernel.spi.exceptions.graph.GraphQueryException {@code (EX-GRPH-5002)}
     *         if deletion fails
     */
    void deleteEdge(GraphEdgeDescriptor edge, UUID sourceId, UUID targetId);

    // =========================================================================
    // Node CRUD
    // =========================================================================

    /**
     * Creates or updates a node identified by {@code label} and {@code nodeId} (upsert
     * semantics), with properties passed as an encoded {@link LoanedBuffer} payload (e.g.
     * MessagePack or raw JSON bytes in off-heap memory) to keep the hot path
     * allocation-free.
     *
     * @param label      node label
     * @param nodeId     unique node identifier
     * @param properties encoded node properties payload (caller-owned); {@code null} if
     *                   there are no properties to set
     * @throws eu.exeris.kernel.spi.exceptions.graph.GraphQueryException {@code (EX-GRPH-5002)}
     *         if the upsert fails
     * @implSpec Implementations MUST read {@code properties} without closing it — the
     *           caller owns its lifecycle. A Community-tier implementation MAY decode it
     *           into an intermediate, driver-native representation (a one-time allocation
     *           is acceptable at that tier); an Enterprise-tier implementation MUST write
     *           the raw off-heap bytes directly into the native protocol stream with zero
     *           heap allocation.
     * @implNote The Community binding decodes {@code properties} into a JDBC parameter
     *           set.
     */
    void upsertNode(String label, UUID nodeId, LoanedBuffer properties);

    /**
     * Deletes a node and all connected edges.
     *
     * @param label  node label
     * @param nodeId node identifier
     * @throws eu.exeris.kernel.spi.exceptions.graph.GraphQueryException {@code (EX-GRPH-5002)}
     *         if deletion fails
     */
    void deleteNode(String label, UUID nodeId);

    // =========================================================================
    // Graph Root
    // =========================================================================

    /**
     * Returns the root node of the graph (entry point for traversals).
     *
     * @return UUID of the root node
     * @throws eu.exeris.kernel.spi.exceptions.graph.GraphQueryException {@code (EX-GRPH-5002)}
     *         if the root node cannot be found
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
     * @throws eu.exeris.kernel.spi.exceptions.graph.GraphQueryException {@code (EX-GRPH-5002)}
     *         if the algorithm fails
     */
    PathResult findShortestPath(UUID source, UUID target);

    /**
     * Finds the shortest path between two nodes for a specific edge type.
     *
     * <p>This overload provides the edge descriptor needed by implementations that
     * resolve adjacency from edge-specific storage (for example, one SQL table per
     * edge type). Implementations that do not require edge metadata may rely on the
     * default delegation.
     *
     * @param edge   edge descriptor that defines the traversed relationship set
     * @param source source node ID
     * @param target target node ID
     * @return path result (may indicate not found)
     * @throws eu.exeris.kernel.spi.exceptions.graph.GraphQueryException {@code (EX-GRPH-5002)}
     *         if the algorithm fails
     */
    default PathResult findShortestPath(GraphEdgeDescriptor edge, UUID source, UUID target) {
        Objects.requireNonNull(edge,   "edge must not be null");
        Objects.requireNonNull(source, "source must not be null");
        Objects.requireNonNull(target, "target must not be null");
        return findShortestPath(source, target);
    }

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
     * Closes the session and releases every resource it owns.
     *
     * @implSpec Implementations MUST release any {@link LoanedBuffer} instances that the
     *           session itself allocated and retained (internal read buffers, slab
     *           checkouts). A buffer already returned to the caller (e.g. from
     *           {@link #streamBfsJson(GraphTraversal)}) is <strong>caller-owned</strong> and
     *           MUST NOT be closed here — the caller is responsible for its lifecycle.
     */
    @Override
    void close();
}


