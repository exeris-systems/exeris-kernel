/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.graph;

import eu.exeris.kernel.core.graph.AlgoOrchestrator;
import eu.exeris.kernel.core.graph.GraphSyncService;
import eu.exeris.kernel.core.graph.MatchDslTranspiler;
import eu.exeris.kernel.spi.exceptions.KernelErrorCodes;
import eu.exeris.kernel.spi.exceptions.graph.GraphSyncException;
import eu.exeris.kernel.spi.graph.GraphConfig;
import eu.exeris.kernel.spi.graph.GraphDialect;
import eu.exeris.kernel.spi.graph.GraphEngine;
import eu.exeris.kernel.spi.graph.GraphSession;
import eu.exeris.kernel.spi.graph.algorithm.EdgeWeightFunction;
import eu.exeris.kernel.spi.graph.algorithm.PathFinder;
import eu.exeris.kernel.spi.graph.model.GraphEdgeDescriptor;
import eu.exeris.kernel.spi.graph.model.GraphNodeDescriptor;
import eu.exeris.kernel.spi.graph.model.GraphTraversal;
import eu.exeris.kernel.spi.graph.model.PathResult;
import eu.exeris.kernel.spi.memory.LoanedBuffer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("Community/Core Graph E2E Integration")
class CommunityGraphCoreE2EIntegrationTest {

    private static final UUID START_ID = new UUID(0L, 10L);
    private static final UUID SOURCE_ID = new UUID(0L, 11L);
    private static final UUID TARGET_ID = new UUID(0L, 12L);

    @Test
    @DisplayName("backend routing changes transpiled syntax")
    void backendRoutingChangesTranspiledSyntax() {
        GraphConfig sqlConfig = GraphConfig.create("postgresql");
        GraphConfig cypherConfig = GraphConfig.create("neo4j");

        GraphEdgeDescriptor follows = GraphEdgeDescriptor.create("User", "FOLLOWS", "User");
        GraphTraversal traversal = GraphTraversal.create(START_ID, follows, 1);

        String sqlQuery;
        String cypherQuery;

        try (CommunityGraphEngine sqlEngine = new CommunityGraphEngine(sqlConfig);
             CommunityGraphEngine cypherEngine = new CommunityGraphEngine(cypherConfig)) {
            sqlQuery = new MatchDslTranspiler(sqlEngine.dialect()).transpileBfs(traversal);
            cypherQuery = new MatchDslTranspiler(cypherEngine.dialect()).transpileBfs(traversal);
        }

        assertThat(sqlQuery).contains("SELECT");
        assertThat(sqlQuery).contains("target_id");
        assertThat(sqlQuery).contains("$1");

        assertThat(cypherQuery).contains("MATCH");
        assertThat(cypherQuery).contains("(source)-[:FOLLOWS]->(target)");
        assertThat(cypherQuery).contains("$sourceId");
    }

    @Test
    @DisplayName("sync service commit path uses transaction lifecycle")
    void syncServiceCommitPathUsesTransactionLifecycle() {
        TrackingGraphEngine engine = new TrackingGraphEngine(false);
        GraphSyncService syncService = new GraphSyncService(engine);
        GraphEdgeDescriptor follows = GraphEdgeDescriptor.create("User", "FOLLOWS", "User");

        syncService.syncEdgeUpsert(follows, SOURCE_ID, TARGET_ID, 1.0, "{}");

        TrackingGraphSession session = engine.sessions().getFirst();
        assertThat(session.beganTransaction).isTrue();
        assertThat(session.committed).isTrue();
        assertThat(session.rolledBack).isFalse();
    }

    @Test
    @DisplayName("sync service rollback path and algo orchestration")
    void syncServiceRollbackPathAndAlgoOrchestration() {
        TrackingGraphEngine failingEngine = new TrackingGraphEngine(true);
        GraphSyncService syncService = new GraphSyncService(failingEngine);

        assertThatThrownBy(() -> syncService.syncNodeUpsert("User", SOURCE_ID, null))
                .isInstanceOf(GraphSyncException.class)
                .satisfies(ex -> assertThat(((GraphSyncException) ex).errorCode())
                        .isEqualTo(KernelErrorCodes.EX_GRPH_5003));

        TrackingGraphSession failedSession = failingEngine.sessions().getFirst();
        assertThat(failedSession.beganTransaction).isTrue();
        assertThat(failedSession.committed).isFalse();
        assertThat(failedSession.rolledBack).isTrue();

        PathFinder pathFinder = new TinyPathFinder();
        AlgoOrchestrator orchestrator = new AlgoOrchestrator(pathFinder);

        PathResult result = orchestrator.findShortestPath(SOURCE_ID, TARGET_ID);

        assertThat(result.found()).isTrue();
        assertThat(result.hopCount()).isEqualTo(2);
        assertThat(result.algorithm()).isEqualTo("tiny-shortest-path");
    }

    private static final class TrackingGraphEngine implements GraphEngine {

        private final boolean failNodeUpsert;
        private final List<TrackingGraphSession> sessions = new CopyOnWriteArrayList<>();

        private TrackingGraphEngine(boolean failNodeUpsert) {
            this.failNodeUpsert = failNodeUpsert;
        }

        @Override
        public GraphSession openSession() {
            TrackingGraphSession session = new TrackingGraphSession(failNodeUpsert);
            sessions.add(session);
            return session;
        }

        @Override
        public GraphDialect dialect() {
            return new CommunityGraphDialect("test_graph", "postgresql");
        }

        @Override
        public void registerNodes(List<GraphNodeDescriptor> nodes) {
            // No-op for local integration double.
        }

        @Override
        public void registerEdges(List<GraphEdgeDescriptor> edges) {
            // No-op for local integration double.
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
            return "tracking-graph-engine";
        }

        @Override
        public boolean isRunning() {
            return true;
        }

        @Override
        public void close() {
            // No-op for local integration double.
        }

        private List<TrackingGraphSession> sessions() {
            return sessions;
        }
    }

    private static final class TrackingGraphSession implements GraphSession {

        private final boolean failNodeUpsert;
        private boolean beganTransaction;
        private boolean committed;
        private boolean rolledBack;

        private TrackingGraphSession(boolean failNodeUpsert) {
            this.failNodeUpsert = failNodeUpsert;
        }

        @Override
        public List<UUID> traverseBreadthFirst(GraphTraversal traversal) {
            return List.of();
        }

        @Override
        public LoanedBuffer streamBfsJson(GraphTraversal traversal) {
            throw new UnsupportedOperationException("Not needed in this test");
        }

        @Override
        public void createEdge(GraphEdgeDescriptor edge, UUID sourceId, UUID targetId,
                               double weight, String properties) {
            // No-op for local integration double.
        }

        @Override
        public void upsertEdge(GraphEdgeDescriptor edge, UUID sourceId, UUID targetId,
                               double weight, String properties) {
            // Success path for transaction-lifecycle verification.
        }

        @Override
        public void deleteEdge(GraphEdgeDescriptor edge, UUID sourceId, UUID targetId) {
            // No-op for local integration double.
        }

        @Override
        public void upsertNode(String label, UUID nodeId, LoanedBuffer properties) {
            if (failNodeUpsert) {
                throw new RuntimeException("Injected node upsert failure");
            }
        }

        @Override
        public void deleteNode(String label, UUID nodeId) {
            // No-op for local integration double.
        }

        @Override
        public UUID getRootNode() {
            return START_ID;
        }

        @Override
        public PathResult findShortestPath(UUID source, UUID target) {
            return PathResult.notFound(source, target, "tracking");
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
            // No-op for local integration double.
        }
    }

    private static final class TinyPathFinder implements PathFinder {

        @Override
        public PathResult findShortestPath(UUID source, UUID target, EdgeWeightFunction weightFn) {
            List<UUID> path = List.of(source, new UUID(0L, 99L), target);
            return new PathResult(source, target, path, 2.0, 2, algorithmName());
        }

        @Override
        public Map<UUID, PathResult> findShortestPaths(UUID source, Set<UUID> targets,
                                                       EdgeWeightFunction weightFn) {
            return targets.stream().collect(java.util.stream.Collectors.toUnmodifiableMap(
                    target -> target,
                    target -> findShortestPath(source, target, weightFn)
            ));
        }

        @Override
        public List<PathResult> findKShortestPaths(UUID source, UUID target, int maxPaths,
                                                   EdgeWeightFunction weightFn) {
            return List.of(findShortestPath(source, target, weightFn));
        }

        @Override
        public boolean pathExists(UUID source, UUID target) {
            return true;
        }

        @Override
        public String algorithmName() {
            return "tiny-shortest-path";
        }
    }
}
