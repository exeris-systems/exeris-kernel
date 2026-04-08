/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.community.graph;

import eu.exeris.kernel.spi.graph.GraphConfig;
import eu.exeris.kernel.spi.graph.GraphDialect;
import eu.exeris.kernel.spi.graph.GraphEngine;
import eu.exeris.kernel.spi.graph.GraphSession;
import eu.exeris.kernel.spi.graph.model.GraphEdgeDescriptor;
import eu.exeris.kernel.spi.graph.model.GraphNodeDescriptor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * L1 Unit: {@link CommunityGraphEngine} — contract, dialect, registry.
 *
 * <h2>Coverage</h2>
 * <ul>
 *   <li>engineName() identifies Community tier</li>
 *   <li>dialect() returns a non-null CommunityGraphDialect</li>
 *   <li>openSession() after close() throws IllegalStateException</li>
 *   <li>registerNodes/registerEdges — storage and retrieval</li>
 *   <li>registeredNodes/registeredEdges return defensive copies (UnsupportedOperationException)</li>
 *   <li>isRunning() lifecycle</li>
 * </ul>
 *
 * @since 0.5.0
 */
@DisplayName("L1 Unit: CommunityGraphEngine")
class CommunityGraphEngineTest {

    private static final GraphConfig GRAPH_CONFIG = GraphConfig.create("unit-test-graph");

    private GraphEngine engine;

    @BeforeEach
    void setUp() {
        engine = new CommunityGraphProvider().createEngine(GRAPH_CONFIG);
    }

    @AfterEach
    void tearDown() {
        engine.close();
    }

    // =========================================================================
    // Identity
    // =========================================================================

    @Nested
    @DisplayName("Engine identity")
    class Identity {

        @Test
        @DisplayName("engineName() contains 'Community'")
        void engineNameContainsCommunity() {
            assertThat(engine.engineName()).containsIgnoringCase("Community");
        }

        @Test
        @DisplayName("engineName() is stable across calls")
        void engineNameIsStable() {
            assertThat(engine.engineName()).isEqualTo(engine.engineName());
        }

        @Test
        @DisplayName("dialect() is non-null")
        void dialectIsNonNull() {
            assertThat(engine.dialect()).isNotNull();
        }

        @Test
        @DisplayName("dialect() is a GraphDialect instance")
        void dialectIsCorrectType() {
            GraphDialect dialect = engine.dialect();
            assertThat(dialect).isInstanceOf(GraphDialect.class);
        }
    }

    // =========================================================================
    // Session lifecycle
    // =========================================================================

    @Nested
    @DisplayName("Session lifecycle")
    class SessionLifecycle {

        @Test
        @DisplayName("openSession() returns non-null session when running")
        void openSessionReturnsNonNull() {
            try (GraphSession session = engine.openSession()) {
                assertThat(session).isNotNull();
            }
        }

        @Test
        @DisplayName("isRunning() is true after construction")
        void isRunningInitially() {
            assertThat(engine.isRunning()).isTrue();
        }

        @Test
        @DisplayName("isRunning() is false after close()")
        void isNotRunningAfterClose() {
            engine.close();
            assertThat(engine.isRunning()).isFalse();
        }

        @Test
        @DisplayName("openSession() after close() throws IllegalStateException")
        void openSessionAfterCloseThrows() {
            engine.close();
            assertThatThrownBy(() -> engine.openSession())
                    .isInstanceOf(IllegalStateException.class);
        }

        @Test
        @DisplayName("close() is idempotent — second call does not throw")
        void closeIsIdempotent() {
            engine.close();
            assertThatCode(() -> engine.close()).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("openSession() in Cypher mode supports same-node shortest path contract")
        void openSessionInCypherModeSupportsSameNodeShortestPath() {
            try (GraphEngine cypherEngine = new CommunityGraphEngine(GraphConfig.create("neo4j"));
                 GraphSession session = cypherEngine.openSession()) {
            UUID nodeId = UUID.randomUUID();

            assertThatCode(() -> session.findShortestPath(nodeId, nodeId))
                .doesNotThrowAnyException();
            assertThat(session.findShortestPath(nodeId, nodeId).found()).isTrue();
            }
        }
    }

    // =========================================================================
    // Node/Edge registry
    // =========================================================================

    @Nested
    @DisplayName("Node/Edge registry")
    class Registry {

        @Test
        @DisplayName("registeredNodes() is empty initially")
        void nodesEmptyInitially() {
            assertThat(engine.registeredNodes()).isEmpty();
        }

        @Test
        @DisplayName("registeredEdges() is empty initially")
        void edgesEmptyInitially() {
            assertThat(engine.registeredEdges()).isEmpty();
        }

        @Test
        @DisplayName("registerNodes() persists all descriptors")
        void registerNodesPersists() {
            engine.registerNodes(List.of(
                    GraphNodeDescriptor.create("User", "users"),
                    GraphNodeDescriptor.create("Product", "products")
            ));
            assertThat(engine.registeredNodes())
                    .extracting(GraphNodeDescriptor::nodeLabel)
                    .containsExactlyInAnyOrder("User", "Product");
        }

        @Test
        @DisplayName("registerEdges() persists all descriptors")
        void registerEdgesPersists() {
            engine.registerEdges(List.of(
                    GraphEdgeDescriptor.create("User", "FOLLOWS", "User"),
                    GraphEdgeDescriptor.create("User", "PURCHASED", "Product")
            ));
            assertThat(engine.registeredEdges())
                    .extracting(GraphEdgeDescriptor::edgeType)
                    .containsExactlyInAnyOrder("FOLLOWS", "PURCHASED");
        }

        @Test
        @DisplayName("registeredNodes() returns immutable list — add throws UnsupportedOperationException")
        void registeredNodesIsImmutable() {
            engine.registerNodes(List.of(GraphNodeDescriptor.create("Ref", "ref_table")));
            List<GraphNodeDescriptor> nodes = engine.registeredNodes();
            assertThatThrownBy(() -> nodes.add(GraphNodeDescriptor.create("Rogue", "rogue")))
                    .isInstanceOf(UnsupportedOperationException.class);
        }

        @Test
        @DisplayName("registeredEdges() returns immutable list — add throws UnsupportedOperationException")
        void registeredEdgesIsImmutable() {
            engine.registerEdges(List.of(GraphEdgeDescriptor.create("A", "LINKS", "B")));
            List<GraphEdgeDescriptor> edges = engine.registeredEdges();
            assertThatThrownBy(() -> edges.add(GraphEdgeDescriptor.create("X", "Y", "Z")))
                    .isInstanceOf(UnsupportedOperationException.class);
        }

        @Test
        @DisplayName("registerNodes() with empty list does not throw")
        void registerEmptyNodesIsNoOp() {
            assertThatCode(() -> engine.registerNodes(List.of()))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("registerEdges() with empty list does not throw")
        void registerEmptyEdgesIsNoOp() {
            assertThatCode(() -> engine.registerEdges(List.of()))
                    .doesNotThrowAnyException();
        }
    }
}

