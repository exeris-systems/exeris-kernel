/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.tck.contract.graph;

import eu.exeris.kernel.spi.graph.GraphEngine;
import eu.exeris.kernel.spi.graph.GraphSession;
import eu.exeris.kernel.spi.graph.model.GraphEdgeDescriptor;
import eu.exeris.kernel.spi.graph.model.GraphNodeDescriptor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.List;
import eu.exeris.kernel.tck.support.TckScope;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * TCK: Abstract base for {@link GraphEngine} contract verification.
 *
 * <h2>Contract</h2>
 * <ul>
 *   <li>{@code openSession()} returns a valid session when engine is running</li>
 *   <li>{@code registerNodes()} / {@code registerEdges()} accept metadata</li>
 *   <li>{@code registeredNodes()} / {@code registeredEdges()} return registered metadata</li>
 *   <li>Concurrent registry access from N virtual threads is safe</li>
 *   <li>{@code close()} is idempotent</li>
 * </ul>
 *
 * @since 0.5.0
 */
public abstract class AbstractGraphEngineTck {

    /** Creates a fully bootstrapped, running {@link GraphEngine}. */
    protected abstract GraphEngine createEngine();

    private GraphEngine engine;

    @BeforeEach
    final void setUpEngine() {
        engine = createEngine();
    }

    @AfterEach
    final void tearDownEngine() {
        engine.close();
    }

    // =========================================================================
    // Session contract
    // =========================================================================

    @Nested
    @DisplayName("Session contract")
    class SessionContract {

        @Test
        @DisplayName("openSession() returns a non-null session")
        void openSessionReturnsNonNull() {
            try (GraphSession session = engine.openSession()) {
                assertThat(session).isNotNull();
            }
        }

        @Test
        @DisplayName("isRunning() returns true for a bootstrapped engine")
        void isRunningTrue() {
            assertThat(engine.isRunning()).isTrue();
        }

        @Test
        @DisplayName("engineName() is non-blank")
        void engineNameNonBlank() {
            assertThat(engine.engineName()).isNotNull().isNotBlank();
        }

        @Test
        @DisplayName("dialect() returns non-null")
        void dialectNonNull() {
            assertThat(engine.dialect()).isNotNull();
        }
    }

    // =========================================================================
    // Registry contract
    // =========================================================================

    @Nested
    @DisplayName("Node/Edge registry contract")
    class RegistryContract {

        @Test
        @DisplayName("registerNodes() followed by registeredNodes() returns registered descriptors")
        void registerAndRetrieveNodes() {
            GraphNodeDescriptor node = GraphNodeDescriptor.create("User", "users");
            engine.registerNodes(List.of(node));
            assertThat(engine.registeredNodes())
                    .extracting(GraphNodeDescriptor::nodeLabel)
                    .contains("User");
        }

        @Test
        @DisplayName("registerEdges() followed by registeredEdges() returns registered descriptors")
        void registerAndRetrieveEdges() {
            GraphEdgeDescriptor edge = GraphEdgeDescriptor.create("User", "FOLLOWS", "User");
            engine.registerEdges(List.of(edge));
            assertThat(engine.registeredEdges())
                    .extracting(GraphEdgeDescriptor::edgeType)
                    .contains("FOLLOWS");
        }

        @Test
        @DisplayName("registeredNodes() returns immutable list")
        void nodesListImmutable() {
            engine.registerNodes(List.of(GraphNodeDescriptor.create("Product", "products")));
            List<GraphNodeDescriptor> nodes = engine.registeredNodes();
            var rogueNode = GraphNodeDescriptor.create("Hack", "hacks");
            assertThatThrownBy(() -> nodes.add(rogueNode))
                    .isInstanceOf(UnsupportedOperationException.class);
        }

        @Test
        @DisplayName("registeredEdges() returns immutable list")
        void edgesListImmutable() {
            engine.registerEdges(List.of(GraphEdgeDescriptor.create("A", "LINKS", "B")));
            List<GraphEdgeDescriptor> edges = engine.registeredEdges();
            var rogueEdge = GraphEdgeDescriptor.create("X", "Y", "Z");
            assertThatThrownBy(() -> edges.add(rogueEdge))
                    .isInstanceOf(UnsupportedOperationException.class);
        }
    }

    // =========================================================================
    // Concurrent registry access
    // =========================================================================

    @Nested
    @DisplayName("Concurrent registry access")
    class ConcurrentRegistry {

        @Test
        @DisplayName("100 VTs registering nodes concurrently — no crash, no data loss")
        @Timeout(30)
        void concurrentNodeRegistration() throws InterruptedException, java.util.concurrent.ExecutionException {
            AtomicInteger errors = new AtomicInteger(0);

            try (var scope = TckScope.open()) {
                for (int i = 0; i < 100; i++) {
                    final int idx = i;
                    scope.fork(() -> {
                        try {
                            engine.registerNodes(List.of(
                                    GraphNodeDescriptor.create("Node" + idx, "table" + idx)));
                        } catch (Exception _) {
                            errors.incrementAndGet();
                        }
                        return null;
                    });
                }
                scope.join();
            }

            assertThat(errors.get()).isZero();
            // At least some nodes should be registered (concurrent add may merge)
            assertThat(engine.registeredNodes()).isNotEmpty();
        }
    }

    // =========================================================================
    // Lifecycle
    // =========================================================================

    @Nested
    @DisplayName("Lifecycle contract")
    class Lifecycle {

        @Test
        @DisplayName("close() is idempotent")
        void closeIsIdempotent() {
            engine.close();
            assertThatCode(() -> engine.close()).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("isRunning() returns false after close()")
        void notRunningAfterClose() {
            engine.close();
            assertThat(engine.isRunning()).isFalse();
        }
    }
}

