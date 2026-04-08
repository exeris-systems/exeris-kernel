/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.community.graph;

import eu.exeris.kernel.spi.graph.GraphDialect;
import eu.exeris.kernel.spi.graph.GraphEngine;
import eu.exeris.kernel.spi.graph.GraphSession;
import eu.exeris.kernel.spi.graph.model.GraphEdgeDescriptor;
import eu.exeris.kernel.spi.graph.model.GraphNodeDescriptor;
import eu.exeris.kernel.spi.graph.model.GraphTraversal;
import eu.exeris.kernel.spi.graph.model.PathResult;
import eu.exeris.kernel.spi.memory.LoanedBuffer;
import eu.exeris.kernel.tck.contract.graph.AbstractGraphSyncServiceTck;
import org.junit.jupiter.api.DisplayName;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Community concrete TCK: {@link AbstractGraphSyncServiceTck} backed by
 * a minimal in-memory graph engine for sync and rollback verification.
 *
 * <h2>What this proves for Community tier</h2>
 * <ul>
 *   <li>GraphEngine.openSession() returns usable sessions</li>
 *   <li>Transactional CRUD operations succeed in normal mode</li>
 *   <li>Failure injection causes exceptions during upsert operations</li>
 *   <li>Rollback can be invoked to handle failures</li>
 * </ul>
 *
 * @since 0.5.0
 */
@DisplayName("Community: GraphEngine sync contract TCK")
class CommunityGraphSyncServiceTckTest extends AbstractGraphSyncServiceTck {

    private static final AtomicBoolean FAIL_MODE = new AtomicBoolean(false);

    @Override
    protected GraphEngine createEngine() {
        FAIL_MODE.set(false);
        return new InMemoryGraphEngine();
    }

    @Override
    protected void setSessionFailMode(boolean fail) {
        FAIL_MODE.set(fail);
    }

    // =========================================================================
    // In-memory GraphEngine (test-only, nested static class)
    // =========================================================================

    private static final class InMemoryGraphEngine implements GraphEngine {

        private static final String ENGINE_NAME = "in-memory-sync";
        private final List<UUID> nodes = new ArrayList<>();
        private final List<Edge> edges = new ArrayList<>();
        private volatile boolean running = true;

        @Override
        public GraphSession openSession() {
            if (!running) {
                throw new IllegalStateException("Engine not running");
            }
            return new InMemoryGraphSession(nodes, edges);
        }

        @Override
        public GraphDialect dialect() {
            return new CommunityGraphDialect("in-memory");
        }

        @Override
        public void registerNodes(List<GraphNodeDescriptor> nodes) {
            // No-op for test
        }

        @Override
        public void registerEdges(List<GraphEdgeDescriptor> edges) {
            // No-op for test
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
        public boolean isRunning() {
            return running;
        }

        @Override
        public String engineName() {
            return ENGINE_NAME;
        }

        @Override
        public void close() {
            running = false;
            nodes.clear();
            edges.clear();
        }

        private static final class Edge {
            final GraphEdgeDescriptor descriptor;
            final UUID sourceId;
            final UUID targetId;
            final double weight;

            Edge(GraphEdgeDescriptor descriptor, UUID sourceId, UUID targetId, double weight) {
                this.descriptor = descriptor;
                this.sourceId = sourceId;
                this.targetId = targetId;
                this.weight = weight;
            }
        }
    }

    private static final class InMemoryGraphSession implements GraphSession {

        private final List<UUID> nodes;
        private final List<InMemoryGraphEngine.Edge> edges;

        InMemoryGraphSession(List<UUID> nodes, List<InMemoryGraphEngine.Edge> edges) {
            this.nodes = nodes;
            this.edges = edges;
        }

        @Override
        public List<UUID> traverseBreadthFirst(GraphTraversal traversal) {
            return List.copyOf(nodes);
        }

        @Override
        public LoanedBuffer streamBfsJson(GraphTraversal traversal) {
            throw new UnsupportedOperationException("streamBfsJson not used by this TCK");
        }

        @Override
        public void createEdge(GraphEdgeDescriptor edge, UUID sourceId, UUID targetId,
                               double weight, String properties) {
            throw new UnsupportedOperationException("createEdge not used by this TCK");
        }

        @Override
        public void upsertEdge(GraphEdgeDescriptor edge, UUID sourceId, UUID targetId,
                               double weight, String properties) {
            if (FAIL_MODE.get()) {
                throw new RuntimeException("Session failed during upsertEdge");
            }
            edges.add(new InMemoryGraphEngine.Edge(edge, sourceId, targetId, weight));
        }

        @Override
        public void deleteEdge(GraphEdgeDescriptor edge, UUID sourceId, UUID targetId) {
            if (FAIL_MODE.get()) {
                throw new RuntimeException("Session failed during deleteEdge");
            }
            edges.removeIf(e -> e.sourceId.equals(sourceId) && e.targetId.equals(targetId));
        }

        @Override
        public void upsertNode(String label, UUID nodeId, LoanedBuffer properties) {
            if (FAIL_MODE.get()) {
                throw new RuntimeException("Session failed during upsertNode");
            }
            if (!nodes.contains(nodeId)) {
                nodes.add(nodeId);
            }
        }

        @Override
        public void deleteNode(String label, UUID nodeId) {
            if (FAIL_MODE.get()) {
                throw new RuntimeException("Session failed during deleteNode");
            }
            nodes.remove(nodeId);
        }

        @Override
        public UUID getRootNode() {
            throw new UnsupportedOperationException("getRootNode not used by this TCK");
        }

        @Override
        public PathResult findShortestPath(UUID source, UUID target) {
            throw new UnsupportedOperationException("findShortestPath not used by this TCK");
        }

        @Override
        public void beginTransaction() {
            // No-op for in-memory
        }

        @Override
        public void commit() {
            // No-op for in-memory
        }

        @Override
        public void rollback() {
            // No-op for in-memory
        }

        @Override
        public void close() {
            // No-op for in-memory
        }
    }
}
