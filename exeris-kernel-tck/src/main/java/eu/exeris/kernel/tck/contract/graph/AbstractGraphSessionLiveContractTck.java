/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.tck.contract.graph;

import eu.exeris.kernel.spi.graph.GraphEngine;
import eu.exeris.kernel.spi.graph.GraphSession;
import eu.exeris.kernel.spi.graph.model.GraphEdgeDescriptor;
import eu.exeris.kernel.spi.graph.model.GraphHop;
import eu.exeris.kernel.spi.graph.model.GraphNodeDescriptor;
import eu.exeris.kernel.spi.graph.model.GraphPathSpec;
import eu.exeris.kernel.spi.graph.model.GraphTraversal;
import eu.exeris.kernel.spi.graph.model.PathResult;
import eu.exeris.kernel.spi.memory.LoanedBuffer;
import eu.exeris.kernel.spi.security.ImmutableStorageContext;
import eu.exeris.kernel.spi.security.StorageContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.lang.foreign.ValueLayout;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
@SuppressWarnings("PMD.CouplingBetweenObjects") // Live backend TCK exercises models, sessions, memory and assertions
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

    private static final Pattern UUID_PATTERN =
            Pattern.compile("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

    /**
     * Traverses a path in a system session, asserts no duplicates, and returns the result as a set.
     *
     * @param start start node ID
     * @param hops hops to traverse
     * @return set of reachable terminal node IDs
     */
    protected final Set<UUID> path(UUID start, GraphHop... hops) {
        return inSystemSession(s -> {
            List<UUID> list = s.traversePath(new GraphPathSpec(start, List.of(hops)));
            assertThat(list).doesNotHaveDuplicates();
            return Set.copyOf(list);
        });
    }

    /**
     * Streams a path JSON in a try-with-resources opened before and read after session.close(),
     * parses the JSON array of UUID strings, and returns the result as a set.
     *
     * @param start start node ID
     * @param hops hops to traverse
     * @return set of reachable terminal node IDs
     */
    protected final Set<UUID> pathJson(UUID start, GraphHop... hops) {
        byte[] bytes;
        try (LoanedBuffer buffer = callInScope(ImmutableStorageContext.GLOBAL, () -> {
            try (GraphSession session = engine().openSession()) {
                return session.streamPathJson(new GraphPathSpec(start, List.of(hops)));
            }
        })) {
            bytes = buffer.segment().asSlice(0, buffer.size()).toArray(ValueLayout.JAVA_BYTE);
        }
        return parseUuidJsonArray(bytes);
    }

    /**
     * Executes BFS in a system session, asserts no duplicates, and returns the result as a set.
     *
     * @param traversal traversal configuration
     * @return set of reachable node IDs
     */
    protected final Set<UUID> bfs(GraphTraversal traversal) {
        return inSystemSession(s -> {
            List<UUID> list = s.traverseBreadthFirst(traversal);
            assertThat(list).doesNotHaveDuplicates();
            return Set.copyOf(list);
        });
    }

    /**
     * Streams BFS JSON in a try-with-resources opened before and read after session.close(),
     * parses the JSON array of UUID strings, and returns the result as a set.
     *
     * @param traversal traversal configuration
     * @return set of reachable node IDs
     */
    protected final Set<UUID> bfsJson(GraphTraversal traversal) {
        byte[] bytes;
        try (LoanedBuffer buffer = callInScope(ImmutableStorageContext.GLOBAL, () -> {
            try (GraphSession session = engine().openSession()) {
                return session.streamBfsJson(traversal);
            }
        })) {
            bytes = buffer.segment().asSlice(0, buffer.size()).toArray(ValueLayout.JAVA_BYTE);
        }
        return parseUuidJsonArray(bytes);
    }

    private static Set<UUID> parseUuidJsonArray(byte[] bytes) {
        String json = new String(bytes, StandardCharsets.UTF_8).trim();
        assertThat(json).startsWith("[").endsWith("]");
        Set<UUID> ids = new HashSet<>();
        Matcher matcher = UUID_PATTERN.matcher(json);
        while (matcher.find()) {
            ids.add(UUID.fromString(matcher.group()));
        }
        return Set.copyOf(ids);
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

    @Test
    @DisplayName("a heterogeneous two-hop path returns the terminal frontier of the hops in order")
    void heterogeneousTwoHopPathReturnsTerminalFrontierInOrder() {
        inSystemSession(s -> {
            UUID u = newNode(s, "User");
            UUID p1 = newNode(s, "Product");
            UUID p2 = newNode(s, "Product");
            UUID p3 = newNode(s, "Product");
            UUID s1 = newNode(s, "Product");
            UUID s2 = newNode(s, "Product");
            UUID s3 = newNode(s, "Product");
            link(s, PURCHASED, u, p1);
            link(s, PURCHASED, u, p2);
            link(s, SIMILAR_TO, p1, s1);
            link(s, SIMILAR_TO, p2, s2);
            link(s, VIEWED, u, p3);
            link(s, SIMILAR_TO, p3, s3);

            Set<UUID> forward = path(u, GraphHop.of(PURCHASED), GraphHop.of(SIMILAR_TO));
            assertThat(forward).containsExactlyInAnyOrder(s1, s2);

            Set<UUID> reversed = path(u, GraphHop.of(SIMILAR_TO), GraphHop.of(PURCHASED));
            assertThat(reversed).isEmpty();
            assertThat(reversed).isNotEqualTo(forward);
        });
    }

    @Test
    @DisplayName("a hop that matches nothing yields an empty result, not an error")
    void hopMatchingNothingYieldsEmptyResult() {
        inSystemSession(s -> {
            UUID u = newNode(s, "User");
            UUID p1 = newNode(s, "Product");
            link(s, PURCHASED, u, p1);

            assertThat(path(u, GraphHop.of(PURCHASED), GraphHop.of(NEVER_SEEDED))).isEmpty();
            assertThat(pathJson(u, GraphHop.of(PURCHASED), GraphHop.of(NEVER_SEEDED))).isEmpty();
            assertThat(path(u, GraphHop.of(VIEWED), GraphHop.of(SIMILAR_TO))).isEmpty();
        });
    }

    @Test
    @DisplayName("a ranged hop followed by a fixed hop returns exactly the nodes at the allowed lengths")
    void rangedHopFollowedByFixedHopHonoursLengths() {
        inSystemSession(s -> {
            UUID a = newNode(s, "Product");
            UUID b = newNode(s, "Product");
            UUID c = newNode(s, "Product");
            UUID d = newNode(s, "Product");
            UUID e = newNode(s, "Product");
            link(s, LINKED, a, b);
            link(s, LINKED, b, c);
            link(s, LINKED, c, d);
            link(s, LINKED, d, e);

            Set<UUID> res1 = path(a, new GraphHop(LINKED, 1, 2), GraphHop.of(LINKED));
            assertThat(res1).containsExactlyInAnyOrder(c, d);

            Set<UUID> res2 = path(a, new GraphHop(LINKED, 2, 3));
            assertThat(res2).containsExactlyInAnyOrder(c, d);
        });
    }

    @Test
    @DisplayName("the result holds each node once")
    void resultHoldsEachNodeOnce() {
        inSystemSession(s -> {
            UUID a = newNode(s, "Product");
            UUID b = newNode(s, "Product");
            UUID c = newNode(s, "Product");
            UUID d = newNode(s, "Product");
            link(s, LINKED, a, b);
            link(s, LINKED, a, c);
            link(s, LINKED, b, d);
            link(s, LINKED, c, d);

            List<UUID> res = s.traversePath(new GraphPathSpec(a, List.of(GraphHop.of(LINKED), GraphHop.of(LINKED))));
            assertThat(res).hasSize(1).containsExactly(d);
        });
    }

    @Test
    @DisplayName("a hop may traverse an edge the previous hop traversed")
    void hopMayTraverseEdgePreviousHopTraversed() {
        inSystemSession(s -> {
            UUID u1 = newNode(s, "User");
            UUID u2 = newNode(s, "User");
            UUID x = newNode(s, "Product");
            link(s, PURCHASED, u1, x);
            link(s, PURCHASED, u2, x);

            Set<UUID> res = path(u1, GraphHop.of(PURCHASED),
                    GraphHop.of(directed(PURCHASED, GraphEdgeDescriptor.Direction.INCOMING, false)));
            assertThat(res).containsExactlyInAnyOrder(u1, u2);
        });
    }

    @Test
    @Timeout(60)
    @DisplayName("a cyclic graph terminates and honours exact lengths with walk semantics")
    void cyclicGraphTerminatesAndHonoursExactLengthsWithWalkSemantics() {
        inSystemSession(s -> {
            UUID x = newNode(s, "Product");
            UUID y = newNode(s, "Product");
            link(s, LINKED, x, y);
            link(s, LINKED, y, x);

            Set<UUID> res1 = path(x, new GraphHop(LINKED, 1, 5));
            assertThat(res1).containsExactlyInAnyOrder(x, y);

            Set<UUID> res2 = path(x, new GraphHop(LINKED, 3, 3));
            assertThat(res2).containsExactly(y);
        });
    }

    @Test
    @DisplayName("each direction selects its own edges, and the one-hop methods agree with traversePath")
    void eachDirectionSelectsItsOwnEdgesAndOneHopAgrees() {
        inSystemSession(s -> {
            UUID a = newNode(s, "Product");
            UUID b = newNode(s, "Product");
            UUID c = newNode(s, "Product");
            link(s, LINKED, a, b);
            link(s, LINKED, c, a);

            GraphEdgeDescriptor out = directed(LINKED, GraphEdgeDescriptor.Direction.OUTGOING, false);
            GraphEdgeDescriptor in = directed(LINKED, GraphEdgeDescriptor.Direction.INCOMING, false);
            GraphEdgeDescriptor both = directed(LINKED, GraphEdgeDescriptor.Direction.BOTH, false);
            GraphEdgeDescriptor bi = directed(LINKED, GraphEdgeDescriptor.Direction.OUTGOING, true);

            assertThat(path(a, GraphHop.of(out))).containsExactly(b);
            assertThat(path(a, GraphHop.of(in))).containsExactly(c);
            assertThat(path(a, GraphHop.of(both))).containsExactlyInAnyOrder(b, c);
            assertThat(path(a, GraphHop.of(bi))).containsExactlyInAnyOrder(b, c);

            for (GraphEdgeDescriptor d : List.of(out, in, both, bi)) {
                GraphTraversal t = GraphTraversal.create(a, d, 1);
                Set<UUID> expected = path(a, GraphHop.of(d));
                assertThat(bfs(t)).isEqualTo(expected);
                assertThat(bfsJson(t)).isEqualTo(expected);
            }
        });
    }

    @Test
    @DisplayName("streamPathJson returns the same id set as traversePath in a caller-owned buffer")
    void streamPathJsonReturnsSameIdSetInCallerOwnedBuffer() {
        inSystemSession(s -> {
            UUID u = newNode(s, "User");
            UUID p1 = newNode(s, "Product");
            UUID p2 = newNode(s, "Product");
            UUID p3 = newNode(s, "Product");
            UUID s1 = newNode(s, "Product");
            UUID s2 = newNode(s, "Product");
            UUID s3 = newNode(s, "Product");
            link(s, PURCHASED, u, p1);
            link(s, PURCHASED, u, p2);
            link(s, SIMILAR_TO, p1, s1);
            link(s, SIMILAR_TO, p2, s2);
            link(s, VIEWED, u, p3);
            link(s, SIMILAR_TO, p3, s3);

            GraphHop h1 = GraphHop.of(PURCHASED);
            GraphHop h2 = GraphHop.of(SIMILAR_TO);
            Set<UUID> pathSet = path(u, h1, h2);
            Set<UUID> streamSet = pathJson(u, h1, h2);
            assertThat(pathSet).containsExactlyInAnyOrder(s1, s2);
            assertThat(streamSet).isEqualTo(pathSet);
        });
    }
}
