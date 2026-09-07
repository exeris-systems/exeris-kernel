/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.core.graph;

import eu.exeris.kernel.spi.exceptions.graph.GraphSyncException;
import eu.exeris.kernel.spi.graph.GraphEngine;
import eu.exeris.kernel.spi.graph.GraphSession;
import eu.exeris.kernel.spi.graph.model.GraphEdgeDescriptor;
import eu.exeris.kernel.spi.memory.LoanedBuffer;

import java.util.Objects;
import java.util.UUID;

/**
 * Core: Dual-write consistency orchestrator for L1 Persistence → L2 Graph synchronization.
 *
 * <h2>Responsibility (graph.md §4 Dual-Write Consistency)</h2>
 * <p>When a relational state change occurs in L1 (e.g., a new "User FOLLOWS User" row
 * is inserted), this service ensures the corresponding edge or node is atomically reflected
 * in the L2 Graph structure. If the graph write fails, the operation is rolled back and
 * {@link GraphSyncException} (EX-GRPH-5003) is emitted via JFR.
 *
 * <h2>Cross-Subsystem Boundary (The Wall)</h2>
 * <p>This class operates exclusively via SPI contracts:
 * <ul>
 *   <li>{@link GraphEngine} / {@link GraphSession} for the graph write</li>
 *   <li>{@link LoanedBuffer} for zero-copy property payload transfer</li>
 * </ul>
 * <p>It has zero knowledge of JDBC, Bolt, io_uring, or any driver-level class.
 *
 * <h2>JFR-First</h2>
 * <p>Every sync operation (success and failure) emits a
 * {@link GraphSyncOperationEvent} for JFR telemetry. Failures additionally
 * trigger a {@link GraphSyncFailedEvent} carrying the edge type and rollback detail.
 *
 * <h2>Thread Safety</h2>
 * <p>This class is stateless and safe to share across all Virtual Threads.
 * Each call opens its own {@link GraphSession} and closes it deterministically
 * via try-with-resources.
 *
 * @since 0.5
 */
// PMD.AvoidCatchingGenericException — suppressed at class level: this class is the L2 sync
// boundary. Driver implementations may throw any RuntimeException (JDBC, Bolt, native).
// Catching RuntimeException here is the architecturally correct boundary translation point:
// all driver exceptions are wrapped into GraphSyncException (EX-GRPH-5003) with rollback.
@SuppressWarnings("PMD.AvoidCatchingGenericException")
public final class GraphSyncService {

    private final GraphEngine engine;

    /**
     * Binds this sync service to a single {@link GraphEngine}; every operation below opens
     * and closes its own {@link GraphSession} against it.
     *
     * @param engine the active graph engine (must not be {@code null})
     */
    public GraphSyncService(GraphEngine engine) {
        this.engine = Objects.requireNonNull(engine, "engine must not be null");
    }

    /**
     * Synchronises a node upsert from L1 Persistence into the L2 graph.
     *
     * <p>Called after a successful relational INSERT or UPDATE so the graph node
     * reflects the current state. The caller retains ownership of {@code properties}
     * — this method does NOT close the buffer.
     *
     * @param label      node label (e.g. "User")
     * @param nodeId     node identifier
     * @param properties off-heap properties payload (caller-owned); may be {@code null}
     * @throws GraphSyncException (EX-GRPH-5003) if the graph write fails
     */
    public void syncNodeUpsert(String label, UUID nodeId, LoanedBuffer properties) {
        Objects.requireNonNull(label,  "label must not be null");
        Objects.requireNonNull(nodeId, "nodeId must not be null");
        executeSync("NODE_UPSERT", label, "Node upsert failed during L1→L2 sync",
                session -> session.upsertNode(label, nodeId, properties));
    }

    /**
     * Synchronises a node deletion from L1 Persistence into the L2 graph.
     *
     * @param label  node label
     * @param nodeId node identifier
     * @throws GraphSyncException (EX-GRPH-5003) if the graph write fails
     */
    public void syncNodeDelete(String label, UUID nodeId) {
        Objects.requireNonNull(label,  "label must not be null");
        Objects.requireNonNull(nodeId, "nodeId must not be null");
        executeSync("NODE_DELETE", label, "Node delete failed during L1→L2 sync",
                session -> session.deleteNode(label, nodeId));
    }

    /**
     * Synchronises an edge upsert from L1 Persistence into the L2 graph.
     *
     * @param edge       edge descriptor
     * @param sourceId   source node identifier
     * @param targetId   target node identifier
     * @param weight     edge weight
     * @param properties JSON edge properties (may be {@code null})
     * @throws GraphSyncException (EX-GRPH-5003) if the graph write fails
     */
    public void syncEdgeUpsert(GraphEdgeDescriptor edge, UUID sourceId, UUID targetId,
                               double weight, String properties) {
        Objects.requireNonNull(edge,     "edge must not be null");
        Objects.requireNonNull(sourceId, "sourceId must not be null");
        Objects.requireNonNull(targetId, "targetId must not be null");
        String edgeType = edge.edgeType();
        executeSync("EDGE_UPSERT", edgeType, "Edge upsert failed during L1→L2 sync",
                session -> session.upsertEdge(edge, sourceId, targetId, weight, properties));
    }

    /**
     * Synchronises an edge deletion from L1 Persistence into the L2 graph.
     *
     * @param edge     edge descriptor
     * @param sourceId source node identifier
     * @param targetId target node identifier
     * @throws GraphSyncException (EX-GRPH-5003) if the graph write fails
     */
    public void syncEdgeDelete(GraphEdgeDescriptor edge, UUID sourceId, UUID targetId) {
        Objects.requireNonNull(edge,     "edge must not be null");
        Objects.requireNonNull(sourceId, "sourceId must not be null");
        Objects.requireNonNull(targetId, "targetId must not be null");
        String edgeType = edge.edgeType();
        executeSync("EDGE_DELETE", edgeType, "Edge delete failed during L1→L2 sync",
                session -> session.deleteEdge(edge, sourceId, targetId));
    }

    /**
     * Runs {@code action} inside its own transaction on a freshly opened {@link GraphSession},
     * emitting a {@link GraphSyncOperationEvent} for the attempt and, on failure, rolling back
     * and emitting a {@link GraphSyncFailedEvent} before wrapping the cause in a
     * {@link GraphSyncException}.
     *
     * @param operationType one of {@code NODE_UPSERT}, {@code NODE_DELETE}, {@code EDGE_UPSERT},
     *                       {@code EDGE_DELETE} — recorded on the JFR event
     * @param label          the node label or edge type being synchronised
     * @param failureMessage message attached to {@link GraphSyncException} on failure
     * @param action         the single write to run against the open session
     * @throws GraphSyncException (EX-GRPH-5003) if {@code action} or the commit throws
     */
    private void executeSync(String operationType, String label, String failureMessage, SessionAction action) {
        final GraphSyncOperationEvent event = GraphSyncOperationEvent.beginIfEnabled();
        if (event != null) {
            event.operationType = operationType;
            event.edgeOrLabel   = label;
        }
        try (GraphSession session = engine.openSession()) {
            session.beginTransaction();
            try {
                action.execute(session);
                session.commit();
                if (event != null) {
                    event.success = true;
                }
            } catch (RuntimeException cause) {
                try {
                    session.rollback();
                } catch (RuntimeException rollbackFailure) {
                    cause.addSuppressed(rollbackFailure);
                }
                GraphSyncFailedEvent.emit(label, cause.getMessage());
                throw new GraphSyncException(label, failureMessage, cause);
            }
        } finally {
            if (event != null) {
                event.commit();
            }
        }
    }

    /** One write against an open {@link GraphSession}, run inside {@link #executeSync}'s transaction. */
    @FunctionalInterface
    private interface SessionAction {
        void execute(GraphSession session);
    }
}
