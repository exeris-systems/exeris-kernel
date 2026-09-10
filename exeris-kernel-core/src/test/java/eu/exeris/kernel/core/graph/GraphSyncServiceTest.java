/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.core.graph;

import eu.exeris.kernel.spi.exceptions.KernelErrorCodes;
import eu.exeris.kernel.spi.exceptions.graph.GraphSyncException;
import eu.exeris.kernel.spi.graph.GraphDialect;
import eu.exeris.kernel.spi.graph.GraphEngine;
import eu.exeris.kernel.spi.graph.GraphSession;
import eu.exeris.kernel.spi.graph.model.GraphEdgeDescriptor;
import eu.exeris.kernel.spi.graph.model.GraphNodeDescriptor;
import eu.exeris.kernel.spi.graph.model.GraphTraversal;
import eu.exeris.kernel.spi.graph.model.PathResult;
import eu.exeris.kernel.spi.memory.LoanedBuffer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * L2 Integration Tests: {@link GraphSyncService} — dual-write orchestration,
 * rollback on failure, EX-GRPH-5003 emission.
 *
 * @since 0.5.0
 */
@DisplayName("L2: GraphSyncService — dual-write, rollback, EX-GRPH-5003")
class GraphSyncServiceTest {

    // =========================================================================
    // Test infrastructure
    // =========================================================================

    private enum SessionOp { NODE_UPSERT, NODE_DELETE, EDGE_UPSERT, EDGE_DELETE }

    private static final class RecordingSession implements GraphSession {
        final List<SessionOp> ops         = new ArrayList<>();
        boolean               shouldFail  = false;
        boolean               committed   = false;
        boolean               rolledBack  = false;
        boolean               txStarted   = false;

        @Override public void beginTransaction()     { txStarted  = true; }
        @Override public void commit()               { committed  = true; }
        @Override public void rollback()             { rolledBack = true; }

        @Override public void upsertNode(String l, UUID id, LoanedBuffer p) {
            if (shouldFail) throw new RuntimeException("simulated upsert failure");
            ops.add(SessionOp.NODE_UPSERT);
        }
        @Override public void deleteNode(String l, UUID id) {
            if (shouldFail) throw new RuntimeException("simulated delete failure");
            ops.add(SessionOp.NODE_DELETE);
        }
        @Override public void upsertEdge(GraphEdgeDescriptor e, UUID src, UUID tgt,
                                         double w, String p) {
            if (shouldFail) throw new RuntimeException("simulated edge upsert failure");
            ops.add(SessionOp.EDGE_UPSERT);
        }
        @Override public void deleteEdge(GraphEdgeDescriptor e, UUID src, UUID tgt) {
            if (shouldFail) throw new RuntimeException("simulated edge delete failure");
            ops.add(SessionOp.EDGE_DELETE);
        }
        @Override public void createEdge(GraphEdgeDescriptor e, UUID src, UUID tgt,
                                         double w, String p) { /* test stub — not exercised in this path */ }
        @Override public List<UUID> traverseBreadthFirst(GraphTraversal t) { return List.of(); }
        @Override public LoanedBuffer streamBfsJson(GraphTraversal t) { return null; }
        @Override public UUID getRootNode() { return UUID.randomUUID(); }
        @Override public PathResult findShortestPath(UUID src, UUID tgt) {
            return PathResult.notFound(src, tgt, "stub");
        }
        @Override public void close() { /* test stub — no resources to release */ }
    }

    private RecordingSession session;
    private GraphEngine      stubEngine;
    private GraphSyncService syncService;

    @BeforeEach
    void setUp() {
        session = new RecordingSession();
        stubEngine = new GraphEngine() {
            @Override public GraphSession openSession() { return session; }
            @Override public GraphDialect dialect()     { return null; }
            @Override public void registerNodes(List<GraphNodeDescriptor> n) { /* test stub — registration not asserted */ }
            @Override public void registerEdges(List<GraphEdgeDescriptor> e) { /* test stub — registration not asserted */ }
            @Override public List<GraphNodeDescriptor> registeredNodes() { return List.of(); }
            @Override public List<GraphEdgeDescriptor> registeredEdges() { return List.of(); }
            @Override public String engineName()  { return "Stub"; }
            @Override public boolean isRunning()  { return true; }
            @Override public void close()         { /* test stub — no resources to release */ }
        };
        syncService = new GraphSyncService(stubEngine);
    }

    // =========================================================================
    // Constructor
    // =========================================================================

    @Test
    @DisplayName("null engine → NullPointerException")
    void nullEngineRejected() {
        assertThatThrownBy(() -> new GraphSyncService(null))
                .isInstanceOf(NullPointerException.class);
    }

    // =========================================================================
    // Node upsert
    // =========================================================================

    @Nested
    @DisplayName("syncNodeUpsert()")
    class NodeUpsert {

        @Test
        @DisplayName("happy path: upsertNode called, tx committed")
        void happyPath() {
            syncService.syncNodeUpsert("User", UUID.randomUUID(), null);
            assertThat(session.txStarted).isTrue();
            assertThat(session.ops).contains(SessionOp.NODE_UPSERT);
            assertThat(session.committed).isTrue();
            assertThat(session.rolledBack).isFalse();
        }

        @Test
        @DisplayName("failure: tx rolled back and GraphSyncException (EX-GRPH-5003) thrown")
        void failureRollsBack() {
            session.shouldFail = true;
            assertThatThrownBy(() -> syncService.syncNodeUpsert("User", UUID.randomUUID(), null))
                    .isInstanceOf(GraphSyncException.class)
                    .satisfies(e -> assertThat(((GraphSyncException) e).errorCode())
                            .isEqualTo(KernelErrorCodes.EX_GRPH_5003));
            assertThat(session.rolledBack).isTrue();
            assertThat(session.committed).isFalse();
        }

        @Test
        @DisplayName("null label → NullPointerException before any session call")
        void nullLabelRejected() {
            assertThatThrownBy(() -> syncService.syncNodeUpsert(null, UUID.randomUUID(), null))
                    .isInstanceOf(NullPointerException.class);
        }
    }

    // =========================================================================
    // Node delete
    // =========================================================================

    @Nested
    @DisplayName("syncNodeDelete()")
    class NodeDelete {

        @Test
        @DisplayName("happy path: deleteNode called, tx committed")
        void happyPath() {
            syncService.syncNodeDelete("User", UUID.randomUUID());
            assertThat(session.ops).contains(SessionOp.NODE_DELETE);
            assertThat(session.committed).isTrue();
        }

        @Test
        @DisplayName("failure: rolls back and throws EX-GRPH-5003")
        void failureRollsBack() {
            session.shouldFail = true;
            assertThatThrownBy(() -> syncService.syncNodeDelete("User", UUID.randomUUID()))
                    .isInstanceOf(GraphSyncException.class);
            assertThat(session.rolledBack).isTrue();
        }
    }

    // =========================================================================
    // Edge upsert
    // =========================================================================

    @Nested
    @DisplayName("syncEdgeUpsert()")
    class EdgeUpsert {

        private final GraphEdgeDescriptor follows =
                GraphEdgeDescriptor.create("User", "FOLLOWS", "User");

        @Test
        @DisplayName("happy path: upsertEdge called, tx committed")
        void happyPath() {
            syncService.syncEdgeUpsert(follows, UUID.randomUUID(), UUID.randomUUID(), 1.0, null);
            assertThat(session.ops).contains(SessionOp.EDGE_UPSERT);
            assertThat(session.committed).isTrue();
        }

        @Test
        @DisplayName("failure: rolls back and throws EX-GRPH-5003")
        void failureRollsBack() {
            session.shouldFail = true;
            assertThatThrownBy(() -> syncService
                    .syncEdgeUpsert(follows, UUID.randomUUID(), UUID.randomUUID(), 1.0, null))
                    .isInstanceOf(GraphSyncException.class);
            assertThat(session.rolledBack).isTrue();
        }
    }

    // =========================================================================
    // Edge delete
    // =========================================================================

    @Nested
    @DisplayName("syncEdgeDelete()")
    class EdgeDelete {

        private final GraphEdgeDescriptor follows =
                GraphEdgeDescriptor.create("User", "FOLLOWS", "User");

        @Test
        @DisplayName("happy path: deleteEdge called, tx committed")
        void happyPath() {
            syncService.syncEdgeDelete(follows, UUID.randomUUID(), UUID.randomUUID());
            assertThat(session.ops).contains(SessionOp.EDGE_DELETE);
            assertThat(session.committed).isTrue();
        }

        @Test
        @DisplayName("failure: rolls back and throws EX-GRPH-5003")
        void failureRollsBack() {
            session.shouldFail = true;
            assertThatThrownBy(() -> syncService
                    .syncEdgeDelete(follows, UUID.randomUUID(), UUID.randomUUID()))
                    .isInstanceOf(GraphSyncException.class);
            assertThat(session.rolledBack).isTrue();
        }
    }
}

