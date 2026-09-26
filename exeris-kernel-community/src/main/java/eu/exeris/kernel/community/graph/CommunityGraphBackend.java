/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.graph;

import eu.exeris.kernel.spi.graph.model.GraphEdgeDescriptor;
import eu.exeris.kernel.spi.graph.model.GraphTraversal;
import eu.exeris.kernel.spi.memory.LoanedBuffer;

import java.util.List;
import java.util.UUID;

/**
 * Backend seam behind {@link CommunityGraphSession}, implemented once per graph dialect:
 * {@link CommunityGraphCypherHelper} for Cypher backends, {@link CommunityGraphSqlHelper}
 * for SQL/PGQ. Most operations mirror their {@link eu.exeris.kernel.spi.graph.GraphSession}
 * namesake one-to-one — see that interface for those operation contracts — so the session
 * facade dispatches those calls to whichever implementation the configured dialect selected
 * without branching on backend type. {@link #loadAdjacency} has no {@code GraphSession}
 * counterpart: it exists only so {@link CommunityGraphSession} can implement
 * {@code findShortestPath} itself, on top of {@link CommunityPathFinder}, instead of
 * delegating those two operations to this backend.
 *
 * <p>{@link #beginTransaction()}, {@link #commit()}, {@link #rollback()} and {@link #close()}
 * default to no-ops. {@code CommunityGraphSqlHelper} does not override them: the SQL/PGQ
 * backend has no explicit transaction lifecycle of its own, and each statement is its own
 * unit of work. {@code CommunityGraphCypherHelper} overrides all four to manage a real Neo4j
 * session and transaction.
 */
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

    /**
     * Loads every edge of {@code edge}'s relationship type into {@code builder}, in both
     * directions when {@code edge} is bidirectional or its direction is
     * {@link GraphEdgeDescriptor.Direction#BOTH}.
     *
     * @param edge    relationship type to load adjacency for
     * @param builder accumulator obtained from {@link CommunityPathFinder#builder()}; the
     *                caller builds a {@link CommunityPathFinder} from it once loading finishes
     * @throws eu.exeris.kernel.spi.exceptions.graph.GraphQueryException ({@code EX-GRPH-5002})
     *         if loading adjacency from the backend fails
     */
    void loadAdjacency(GraphEdgeDescriptor edge, CommunityPathFinder.Builder builder);

    /**
     * Begins a transaction. The default is a no-op; see the type-level note on which
     * implementation relies on that default.
     */
    default void beginTransaction() {
    }

    /**
     * Commits the current transaction. The default is a no-op.
     */
    default void commit() {
    }

    /**
     * Rolls back the current transaction. The default is a no-op.
     */
    default void rollback() {
    }

    /**
     * Releases backend resources. The default is a no-op.
     */
    @Override
    default void close() {
    }
}
