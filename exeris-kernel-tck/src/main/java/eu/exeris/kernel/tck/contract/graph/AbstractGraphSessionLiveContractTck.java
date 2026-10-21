/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.tck.contract.graph;

import eu.exeris.kernel.spi.graph.GraphEngine;
import eu.exeris.kernel.spi.graph.GraphSession;
import eu.exeris.kernel.spi.graph.model.GraphEdgeDescriptor;
import eu.exeris.kernel.spi.graph.model.GraphNodeDescriptor;
import eu.exeris.kernel.spi.graph.model.GraphTraversal;
import eu.exeris.kernel.spi.graph.model.PathResult;
import eu.exeris.kernel.spi.security.ImmutableStorageContext;
import eu.exeris.kernel.spi.security.StorageContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * TCK: Observable contract for {@link GraphSession} against a live backend (PostgreSQL, Neo4j).
 *
 * <p>A binding registers {@link #nodeTypes()} and {@link #edgeTypes()} once under the system context
 * and implements {@link #callInScope(StorageContext, Supplier)}.
 *
 * @since 0.13
 */
@DisplayName("TCK: GraphSession — contract against a live backend")
public abstract class AbstractGraphSessionLiveContractTck {

    /** User -> Product. */
    protected static final GraphEdgeDescriptor PURCHASED = edge("User", "PURCHASED", "Product", "tck_purchased_edges");
    /** User -> Product; a second User-sourced type, so a query that ignores the edge type is caught. */
    protected static final GraphEdgeDescriptor VIEWED = edge("User", "VIEWED", "Product", "tck_viewed_edges");
    /** Product -> Product. */
    protected static final GraphEdgeDescriptor SIMILAR_TO = edge("Product", "SIMILAR_TO", "Product", "tck_similar_to_edges");
    /** Product -> Product, used for chains, cycles, direction and shortest path. */
    protected static final GraphEdgeDescriptor LINKED = edge("Product", "LINKED", "Product", "tck_linked_edges");
    /** Product -> Product, never seeded: the empty hop. */
    protected static final GraphEdgeDescriptor NEVER_SEEDED = edge("Product", "NEVER_SEEDED", "Product", "tck_never_seeded_edges");

    /**
     * Creates the live contract TCK.
     */
    public AbstractGraphSessionLiveContractTck() {
        super();
    }

    /**
     * Node types a binding registers once, under the system context, before the first case.
     *
     * @return the immutable list of node descriptors
     */
    public static List<GraphNodeDescriptor> nodeTypes() {
        return List.of(
                GraphNodeDescriptor.create("User", "tck_users"),
                GraphNodeDescriptor.create("Product", "tck_products")
        );
    }

    /**
     * Edge types a binding registers once, under the system context, before the first case.
     *
     * @return the immutable list of edge descriptors
     */
    public static List<GraphEdgeDescriptor> edgeTypes() {
        return List.of(PURCHASED, VIEWED, SIMILAR_TO, LINKED, NEVER_SEEDED);
    }

    /**
     * The live engine; the same instance for every case of a binding class, with nodeTypes()/edgeTypes() registered.
     *
     * @return the live graph engine instance
     */
    protected abstract GraphEngine engine();

    /**
     * Runs action with STORAGE_CONTEXT bound to context and every other binding the backend needs on this thread.
     *
     * @param <T> result type
     * @param context the storage context to bind
     * @param action the action to execute in scope
     * @return result of the action
     */
    protected abstract <T> T callInScope(StorageContext context, Supplier<T> action);

    /**
     * Executes work inside a session opened under the system context (GLOBAL).
     *
     * @param <T> result type
     * @param work the function to execute with an open session
     * @return result of work
     */
    protected final <T> T inSystemSession(Function<GraphSession, T> work) {
        return callInScope(ImmutableStorageContext.GLOBAL, () -> {
            try (GraphSession session = engine().openSession()) {
                return work.apply(session);
            }
        });
    }

    /**
     * Executes an action inside a session opened under the system context (GLOBAL).
     *
     * @param work the consumer to execute with an open session
     */
    protected final void inSystemSession(Consumer<GraphSession> work) {
        inSystemSession(s -> {
            work.accept(s);
            return null;
        });
    }

    /**
     * Creates a new node with the specified label and a random UUID under the session.
     *
     * @param s graph session
     * @param label node label
     * @return generated node ID
     */
    protected final UUID newNode(GraphSession s, String label) {
        UUID id = UUID.randomUUID();
        s.upsertNode(label, id, null);
        return id;
    }

    /**
     * Links two nodes with weight 1.0.
     *
     * @param s graph session
     * @param e edge descriptor
     * @param a source node ID
     * @param b target node ID
     */
    protected final void link(GraphSession s, GraphEdgeDescriptor e, UUID a, UUID b) {
        s.createEdge(e, a, b, 1.0, null);
    }

    /**
     * Links two nodes with specified weight.
     *
     * @param s graph session
     * @param e edge descriptor
     * @param a source node ID
     * @param b target node ID
     * @param w edge weight
     */
    protected final void link(GraphSession s, GraphEdgeDescriptor e, UUID a, UUID b, double w) {
        s.createEdge(e, a, b, w, null);
    }

    /**
     * Creates a copy of an edge descriptor with the given direction and bidirectionality.
     *
     * @param e original descriptor
     * @param d direction
     * @param bidirectional whether bidirectional
     * @return new edge descriptor
     */
    protected static GraphEdgeDescriptor directed(GraphEdgeDescriptor e, GraphEdgeDescriptor.Direction d, boolean bidirectional) {
        return new GraphEdgeDescriptor(e.sourceNode(), e.edgeType(), e.targetNode(), e.weight(), bidirectional, d, e.tableName());
    }

    private static GraphEdgeDescriptor edge(String src, String type, String tgt, String table) {
        return new GraphEdgeDescriptor(src, type, tgt, 1.0, false, GraphEdgeDescriptor.Direction.OUTGOING, table);
    }

    @Test
    @DisplayName("findShortestPath over a seeded connected pair finds the cheapest path through a session")
    void findShortestPathOverSeededConnectedPairFindsCheapestPath() {
        inSystemSession(s -> {
            UUID a = newNode(s, "Product");
            UUID b = newNode(s, "Product");
            UUID c = newNode(s, "Product");
            link(s, LINKED, a, b, 1.0);
            link(s, LINKED, b, c, 1.0);
            link(s, LINKED, a, c, 5.0);

            PathResult result = s.findShortestPath(LINKED, a, c);
            assertThat(result.found()).isTrue();
            assertThat(result.path()).containsExactly(a, b, c);
            assertThat(result.hopCount()).isEqualTo(2);
            assertThat(result.totalCost()).isCloseTo(2.0, within(1e-9));
        });
    }

    @Test
    @DisplayName("findShortestPath between unconnected nodes reports not found")
    void findShortestPathBetweenUnconnectedNodesReportsNotFound() {
        inSystemSession(s -> {
            UUID a = newNode(s, "Product");
            UUID b = newNode(s, "Product");
            UUID z = newNode(s, "Product");
            link(s, LINKED, a, b, 1.0);

            PathResult result = s.findShortestPath(LINKED, a, z);
            assertThat(result.found()).isFalse();
            assertThat(result.source()).isEqualTo(a);
            assertThat(result.target()).isEqualTo(z);
        });
    }

    @Test
    @DisplayName("deleteNode removes every edge connected to the node on both sides and leaves other edges")
    void deleteNodeRemovesConnectedEdgesOnBothSidesAndLeavesOtherEdges() {
        inSystemSession(s -> {
            UUID u = newNode(s, "User");
            UUID p1 = newNode(s, "Product");
            UUID p2 = newNode(s, "Product");
            link(s, PURCHASED, u, p1);
            link(s, VIEWED, u, p2);
            link(s, SIMILAR_TO, p1, p2);

            s.deleteNode("Product", p1);

            List<UUID> resPurchased = s.traverseBreadthFirst(GraphTraversal.create(u, PURCHASED, 1));
            assertThat(resPurchased).isEmpty();

            List<UUID> resSimilar = s.traverseBreadthFirst(GraphTraversal.create(p1, SIMILAR_TO, 1));
            assertThat(resSimilar).isEmpty();

            List<UUID> resViewed = s.traverseBreadthFirst(GraphTraversal.create(u, VIEWED, 1));
            assertThat(resViewed).containsExactly(p2);
        });
    }

    @Test
    @DisplayName("deleteNode with a label the node does not carry removes nothing")
    void deleteNodeWithLabelTheNodeDoesNotCarryRemovesNothing() {
        inSystemSession(s -> {
            UUID u = newNode(s, "User");
            UUID p1 = newNode(s, "Product");
            UUID p2 = newNode(s, "Product");
            link(s, PURCHASED, u, p1);
            link(s, SIMILAR_TO, p1, p2);

            s.deleteNode("User", p1);

            List<UUID> resPurchased = s.traverseBreadthFirst(GraphTraversal.create(u, PURCHASED, 1));
            assertThat(resPurchased).containsExactly(p1);

            List<UUID> resSimilar = s.traverseBreadthFirst(GraphTraversal.create(p1, SIMILAR_TO, 1));
            assertThat(resSimilar).containsExactly(p2);
        });
    }

    @Test
    @DisplayName("a deleted node and its edge can be created again")
    void deletedNodeAndItsEdgeCanBeCreatedAgain() {
        inSystemSession(s -> {
            UUID u = newNode(s, "User");
            UUID p1 = newNode(s, "Product");
            UUID p2 = newNode(s, "Product");
            link(s, PURCHASED, u, p1);
            link(s, VIEWED, u, p2);
            link(s, SIMILAR_TO, p1, p2);

            s.deleteNode("Product", p1);

            s.upsertNode("Product", p1, null);
            link(s, PURCHASED, u, p1);

            List<UUID> resPurchased = s.traverseBreadthFirst(GraphTraversal.create(u, PURCHASED, 1));
            assertThat(resPurchased).containsExactly(p1);
        });
    }

    @Test
    @DisplayName("traverseBreadthFirst returns the nodes within depth along the outgoing edges")
    void traverseBreadthFirstReturnsNodesWithinDepthAlongOutgoingEdges() {
        inSystemSession(s -> {
            UUID a = newNode(s, "Product");
            UUID b = newNode(s, "Product");
            UUID c = newNode(s, "Product");
            UUID d = newNode(s, "Product");
            link(s, LINKED, a, b);
            link(s, LINKED, b, c);
            link(s, LINKED, c, d);

            List<UUID> res = s.traverseBreadthFirst(GraphTraversal.create(a, LINKED, 2));
            assertThat(res)
                    .containsExactlyInAnyOrder(b, c)
                    .doesNotContain(d, a);
        });
    }
}
