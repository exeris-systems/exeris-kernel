/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.graph;

import eu.exeris.kernel.core.graph.GraphSyncService;
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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("L1 Integration: GraphSyncService with in-memory graph engine")
class CommunityGraphSyncServiceCoreIntegrationTest {

    private static final UUID NODE_ID = new UUID(0L, 1L);
    private static final UUID SOURCE_ID = new UUID(0L, 2L);
    private static final UUID TARGET_ID = new UUID(0L, 3L);
    private static final UUID ROOT_ID = new UUID(0L, 4L);

    @Test
    @DisplayName("happy path: node and edge upsert begin+commit without rollback")
    void happyPathCommitsWithoutRollback() {
        InMemoryGraphEngine engine = new InMemoryGraphEngine(false);
        GraphSyncService service = new GraphSyncService(engine);
        GraphEdgeDescriptor edge = GraphEdgeDescriptor.create("User", "FOLLOWS", "User");

        service.syncNodeUpsert("User", NODE_ID, null);
        service.syncEdgeUpsert(edge, SOURCE_ID, TARGET_ID, 1.0, "{}");

        assertThat(engine.sessions()).hasSize(2);
        assertThat(engine.sessions().get(0).beganTransaction).isTrue();
        assertThat(engine.sessions().get(0).committed).isTrue();
        assertThat(engine.sessions().get(0).rolledBack).isFalse();
        assertThat(engine.sessions().get(1).beganTransaction).isTrue();
        assertThat(engine.sessions().get(1).committed).isTrue();
        assertThat(engine.sessions().get(1).rolledBack).isFalse();
    }

    @Test
    @DisplayName("failure path: runtime exception becomes EX-GRPH-5003 and rollback")
    void failurePathRollsBackAndWrapsError() {
        InMemoryGraphEngine engine = new InMemoryGraphEngine(true);
        GraphSyncService service = new GraphSyncService(engine);

        assertThatThrownBy(() -> service.syncNodeUpsert("User", NODE_ID, null))
                .isInstanceOf(GraphSyncException.class)
                .satisfies(throwable -> {
                    GraphSyncException exception = (GraphSyncException) throwable;
                    assertThat(exception.errorCode()).isEqualTo(KernelErrorCodes.EX_GRPH_5003);
                });

        assertThat(engine.sessions()).hasSize(1);
        assertThat(engine.sessions().get(0).beganTransaction).isTrue();
        assertThat(engine.sessions().get(0).committed).isFalse();
        assertThat(engine.sessions().get(0).rolledBack).isTrue();
    }

    private static final class InMemoryGraphEngine implements GraphEngine {

        private final boolean failNodeUpsert;
        private final List<InMemoryGraphSession> sessions = new java.util.concurrent.CopyOnWriteArrayList<>();

        private InMemoryGraphEngine(boolean failNodeUpsert) {
            this.failNodeUpsert = failNodeUpsert;
        }

        @Override
        public GraphSession openSession() {
            InMemoryGraphSession session = new InMemoryGraphSession(failNodeUpsert);
            sessions.add(session);
            return session;
        }

        @Override
        public GraphDialect dialect() {
            return new CommunityGraphDialect("test_graph");
        }

        @Override
        public void registerNodes(List<GraphNodeDescriptor> nodes) {
            // no-op for test engine
        }

        @Override
        public void registerEdges(List<GraphEdgeDescriptor> edges) {
            // no-op for test engine
        }

        @Override
        public List<GraphNodeDescriptor> registeredNodes() {
            return List.of();
        }

        @Override
        public List<GraphEdgeDescriptor> registeredEdges() {
            return List.of();
        }

        @Override
        public String engineName() {
            return "InMemoryGraphEngine";
        }

        @Override
        public boolean isRunning() {
            return true;
        }

        @Override
        public void close() {
            // no-op for test engine
        }

        private List<InMemoryGraphSession> sessions() {
            return sessions;
        }
    }

    private static final class InMemoryGraphSession implements GraphSession {

        private final boolean failNodeUpsert;
        private boolean beganTransaction;
        private boolean committed;
        private boolean rolledBack;

        private InMemoryGraphSession(boolean failNodeUpsert) {
            this.failNodeUpsert = failNodeUpsert;
        }

        @Override
        public List<UUID> traverseBreadthFirst(GraphTraversal traversal) {
            return List.of();
        }

        @Override
        public LoanedBuffer streamBfsJson(GraphTraversal traversal) {
            throw new UnsupportedOperationException("Not needed for this test");
        }

        @Override
        public void createEdge(GraphEdgeDescriptor edge, UUID sourceId, UUID targetId, double weight, String properties) {
            // no-op for this test
        }

        @Override
        public void upsertEdge(GraphEdgeDescriptor edge, UUID sourceId, UUID targetId, double weight, String properties) {
            // success path by default
        }

        @Override
        public void deleteEdge(GraphEdgeDescriptor edge, UUID sourceId, UUID targetId) {
            // no-op for this test
        }

        @Override
        public void upsertNode(String label, UUID nodeId, LoanedBuffer properties) {
            if (failNodeUpsert) {
                throw new RuntimeException("injected session failure");
            }
        }

        @Override
        public void deleteNode(String label, UUID nodeId) {
            // no-op for this test
        }

        @Override
        public UUID getRootNode() {
            return ROOT_ID;
        }

        @Override
        public PathResult findShortestPath(UUID source, UUID target) {
            return PathResult.notFound(source, target, "test");
        }

        @Override
        public void beginTransaction() {
            beganTransaction = true;
        }

        @Override
        public void commit() {
            committed = true;
        }

        @Override
        public void rollback() {
            rolledBack = true;
        }

        @Override
        public void close() {
            // no-op for this test
        }
    }
}
