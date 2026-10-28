/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.graph;

import eu.exeris.kernel.community.persistence.PersistenceSessionBox;
import eu.exeris.kernel.spi.context.KernelProviders;
import eu.exeris.kernel.spi.exceptions.graph.GraphQueryException;
import eu.exeris.kernel.spi.graph.GraphConfig;
import eu.exeris.kernel.spi.graph.GraphEngine;
import eu.exeris.kernel.spi.graph.GraphSession;
import eu.exeris.kernel.spi.graph.model.GraphEdgeDescriptor;
import eu.exeris.kernel.spi.graph.model.GraphHop;
import eu.exeris.kernel.spi.graph.model.GraphPathSpec;
import eu.exeris.kernel.spi.graph.model.GraphTraversal;
import eu.exeris.kernel.spi.persistence.PersistenceEngine;
import eu.exeris.kernel.spi.persistence.TransactionIsolation;
import eu.exeris.kernel.spi.security.ImmutableStorageContext;
import eu.exeris.kernel.spi.security.StorageContext;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * L1 Unit: {@link CommunityGraphSession} path limit guard.
 *
 * <p>Verifies that traversals whose path length bound exceeds the Community limit (64)
 * are refused before any I/O with {@code PATH_LIMIT}, and that a traversal under a tenant
 * context the binding cannot scope is refused with {@code ISOLATION_SCOPE} before a connection
 * is opened.
 *
 * @since 0.13
 */
@DisplayName("L1 Unit: CommunityGraphSession path limit and scope guard")
class CommunityGraphSessionGuardTest {

    private GraphEngine engine;

    CommunityGraphSessionGuardTest() {
        super();
    }

    @BeforeEach
    void setUp() {
        engine = new CommunityGraphEngine(GraphConfig.create("postgresql"));
    }

    @AfterEach
    void tearDown() {
        engine.close();
    }

    @Test
    @DisplayName("traversePath with hops summing to 65 throws PATH_LIMIT")
    void traversePathExceedingLimitThrowsPathLimit() {
        try (GraphSession session = engine.openSession()) {
            GraphEdgeDescriptor edge = GraphEdgeDescriptor.create("Product", "LINKED", "Product");
            UUID start = UUID.randomUUID();
            GraphPathSpec path = new GraphPathSpec(start, List.of(new GraphHop(edge, 1, 65)));

            assertThatThrownBy(() -> session.traversePath(path))
                    .isInstanceOf(GraphQueryException.class)
                    .satisfies(ex -> {
                        GraphQueryException gqe = (GraphQueryException) ex;
                        assertThat(gqe.rawArgs()[0]).isEqualTo("PATH_LIMIT");
                    });
        }
    }

    @Test
    @DisplayName("traversePath with hops summing to 64 passes limit guard and fails on missing persistence")
    void traversePathAtLimitPassesGuard() {
        try (GraphSession session = engine.openSession()) {
            GraphEdgeDescriptor edge = GraphEdgeDescriptor.create("Product", "LINKED", "Product");
            UUID start = UUID.randomUUID();
            GraphPathSpec path = new GraphPathSpec(start, List.of(new GraphHop(edge, 1, 64)));

            assertThatThrownBy(() -> session.traversePath(path))
                    .isInstanceOf(GraphQueryException.class)
                    .satisfies(ex -> {
                        GraphQueryException gqe = (GraphQueryException) ex;
                        assertThat(gqe.rawArgs()[0]).isNotEqualTo("PATH_LIMIT");
                    });
        }
    }

    @Test
    @DisplayName("traverseBreadthFirst with depth 65 throws PATH_LIMIT")
    void traverseBreadthFirstExceedingLimitThrowsPathLimit() {
        try (GraphSession session = engine.openSession()) {
            GraphEdgeDescriptor edge = GraphEdgeDescriptor.create("Product", "LINKED", "Product");
            UUID start = UUID.randomUUID();
            GraphTraversal traversal = GraphTraversal.create(start, edge, 65);

            assertThatThrownBy(() -> session.traverseBreadthFirst(traversal))
                    .isInstanceOf(GraphQueryException.class)
                    .satisfies(ex -> {
                        GraphQueryException gqe = (GraphQueryException) ex;
                        assertThat(gqe.rawArgs()[0]).isEqualTo("PATH_LIMIT");
                    });
        }
    }

    @Test
    @DisplayName("traversePath with a hop bound of Integer.MAX_VALUE after another hop throws PATH_LIMIT")
    void traversePathSumOverflowingIntThrowsPathLimit() {
        try (GraphSession session = engine.openSession()) {
            GraphEdgeDescriptor edge = GraphEdgeDescriptor.create("Product", "LINKED", "Product");
            GraphPathSpec path = new GraphPathSpec(UUID.randomUUID(),
                    List.of(GraphHop.of(edge), new GraphHop(edge, 1, Integer.MAX_VALUE)));

            assertThatThrownBy(() -> session.traversePath(path))
                    .isInstanceOf(GraphQueryException.class)
                    .satisfies(ex -> assertThat(((GraphQueryException) ex).rawArgs()[0]).isEqualTo("PATH_LIMIT"));
        }
    }

    @Test
    @DisplayName("traversePath with two hops summing to 65 throws PATH_LIMIT")
    void traversePathTwoHopsSummingToSixtyFiveThrowsPathLimit() {
        try (GraphSession session = engine.openSession()) {
            GraphEdgeDescriptor edge = GraphEdgeDescriptor.create("Product", "LINKED", "Product");
            GraphPathSpec path = new GraphPathSpec(UUID.randomUUID(),
                    List.of(new GraphHop(edge, 1, 32), new GraphHop(edge, 1, 33)));

            assertThatThrownBy(() -> session.traversePath(path))
                    .isInstanceOf(GraphQueryException.class)
                    .satisfies(ex -> assertThat(((GraphQueryException) ex).rawArgs()[0]).isEqualTo("PATH_LIMIT"));
        }
    }

    @Test
    @DisplayName("traversePath with two hops summing to 64 passes the limit guard")
    void traversePathTwoHopsSummingToSixtyFourPassesGuard() {
        try (GraphSession session = engine.openSession()) {
            GraphEdgeDescriptor edge = GraphEdgeDescriptor.create("Product", "LINKED", "Product");
            GraphPathSpec path = new GraphPathSpec(UUID.randomUUID(),
                    List.of(new GraphHop(edge, 1, 32), new GraphHop(edge, 1, 32)));

            assertThatThrownBy(() -> session.traversePath(path))
                    .isInstanceOf(GraphQueryException.class)
                    .satisfies(ex -> assertThat(((GraphQueryException) ex).rawArgs()[0]).isNotEqualTo("PATH_LIMIT"));
        }
    }

    @Test
    @DisplayName("streamPathJson with hops summing to 65 throws PATH_LIMIT")
    void streamPathJsonExceedingLimitThrowsPathLimit() {
        try (GraphSession session = engine.openSession()) {
            GraphEdgeDescriptor edge = GraphEdgeDescriptor.create("Product", "LINKED", "Product");
            GraphPathSpec path = new GraphPathSpec(UUID.randomUUID(),
                    List.of(new GraphHop(edge, 1, 32), new GraphHop(edge, 1, 33)));

            assertThatThrownBy(() -> session.streamPathJson(path))
                    .isInstanceOf(GraphQueryException.class)
                    .satisfies(ex -> assertThat(((GraphQueryException) ex).rawArgs()[0]).isEqualTo("PATH_LIMIT"));
        }
    }

    @Test
    @DisplayName("streamBfsJson with depth 65 throws PATH_LIMIT")
    void streamBfsJsonExceedingLimitThrowsPathLimit() {
        try (GraphSession session = engine.openSession()) {
            GraphEdgeDescriptor edge = GraphEdgeDescriptor.create("Product", "LINKED", "Product");
            GraphTraversal traversal = GraphTraversal.create(UUID.randomUUID(), edge, 65);

            assertThatThrownBy(() -> session.streamBfsJson(traversal))
                    .isInstanceOf(GraphQueryException.class)
                    .satisfies(ex -> assertThat(((GraphQueryException) ex).rawArgs()[0]).isEqualTo("PATH_LIMIT"));
        }
    }

    @Test
    @DisplayName("SQL engine refuses all four traversal methods under SHARED tenant context as ISOLATION_SCOPE")
    void sqlEngineRefusesEveryTraversalMethodUnderSharedTenantContext() {
        StorageContext ctx = ImmutableStorageContext.shared("t");
        try (GraphSession session = engine.openSession()) {
            ScopedValue.where(KernelProviders.STORAGE_CONTEXT, ctx).run(() -> {
                assertScopeRefused(() -> session.traversePath(onePathSpec()), "Community SQL/PGQ (PostgreSQL)", "SHARED");
                assertScopeRefused(() -> session.streamPathJson(onePathSpec()), "Community SQL/PGQ (PostgreSQL)", "SHARED");
                assertScopeRefused(() -> session.traverseBreadthFirst(oneTraversal()), "Community SQL/PGQ (PostgreSQL)", "SHARED");
                assertScopeRefused(() -> session.streamBfsJson(oneTraversal()), "Community SQL/PGQ (PostgreSQL)", "SHARED");
            });
        }
    }

    @Test
    @DisplayName("SQL engine passes scope guard under SEPARATED_SCHEMA with a bound engine and under GLOBAL")
    void sqlEnginePassesScopeGuardUnderSeparatedSchemaAndGlobal() {
        StorageContext sep = ImmutableStorageContext.separatedSchema("t", "s");
        AtomicInteger opened = new AtomicInteger();
        PersistenceEngine stub = stubEngine(opened);
        try (GraphSession session = engine.openSession()) {
            ScopedValue.where(KernelProviders.STORAGE_CONTEXT, sep)
                    .where(KernelProviders.PERSISTENCE_ENGINE, stub)
                    .run(() -> assertNotScopeRefused(() -> session.traversePath(onePathSpec())));
            assertThat(opened).as("the engine opened the connection").hasValue(1);

            ScopedValue.where(KernelProviders.STORAGE_CONTEXT, ImmutableStorageContext.GLOBAL).run(() ->
                    assertNotScopeRefused(() -> session.traversePath(onePathSpec())));
        }
    }

    @Test
    @DisplayName("SQL engine passes scope guard under DEDICATED with a bound engine")
    void sqlEnginePassesScopeGuardUnderDedicatedWithBoundEngine() {
        AtomicInteger opened = new AtomicInteger();
        try (GraphSession session = engine.openSession()) {
            ScopedValue.where(KernelProviders.STORAGE_CONTEXT, ImmutableStorageContext.dedicated("t", "d"))
                    .where(KernelProviders.PERSISTENCE_ENGINE, stubEngine(opened))
                    .run(() -> assertNotScopeRefused(() -> session.traversePath(onePathSpec())));
            assertThat(opened).hasValue(1);
        }
    }

    @Test
    @DisplayName("SQL engine refuses every tenant traversal without a bound PersistenceEngine and opens no connection")
    void sqlEngineRefusesTenantTraversalWithoutBoundEngine() {
        AtomicInteger opened = new AtomicInteger();
        PersistenceSessionBox box =
                new PersistenceSessionBox(stubEngine(opened), TransactionIsolation.READ_COMMITTED, false);
        try (GraphSession session = engine.openSession()) {
            for (StorageContext ctx : List.of(ImmutableStorageContext.separatedSchema("t", "s"),
                    ImmutableStorageContext.dedicated("t", "d"))) {
                ScopedValue.where(KernelProviders.STORAGE_CONTEXT, ctx)
                        .where(PersistenceSessionBox.REQUEST_SESSION, box)
                        .run(() -> {
                            assertScopeRefused(() -> session.traversePath(onePathSpec()), "PersistenceEngine");
                            assertScopeRefused(() -> session.streamPathJson(onePathSpec()), "PersistenceEngine");
                            assertScopeRefused(() -> session.traverseBreadthFirst(oneTraversal()), "PersistenceEngine");
                            assertScopeRefused(() -> session.streamBfsJson(oneTraversal()), "PersistenceEngine");
                        });
            }
        }
        assertThat(opened).as("no connection opened").hasValue(0);
    }

    @Test
    @DisplayName("SQL engine serves a system-scope traversal through the request session without a bound engine")
    void sqlEngineServesSystemScopeThroughRequestSessionFallback() {
        AtomicInteger opened = new AtomicInteger();
        PersistenceSessionBox box =
                new PersistenceSessionBox(stubEngine(opened), TransactionIsolation.READ_COMMITTED, false);
        try (GraphSession session = engine.openSession()) {
            ScopedValue.where(KernelProviders.STORAGE_CONTEXT, ImmutableStorageContext.GLOBAL)
                    .where(PersistenceSessionBox.REQUEST_SESSION, box)
                    .run(() -> assertNotScopeRefused(() -> session.traversePath(onePathSpec())));
        }
        assertThat(opened).as("the request session opened the connection").hasValue(1);
    }

    @Test
    @DisplayName("Cypher engine refuses every tenant strategy and passes under GLOBAL")
    void cypherEngineRefusesEveryTenantStrategyAndPassesGlobal() {
        try (CommunityGraphEngine cypherEngine = new CommunityGraphEngine(GraphConfig.create("neo4j"));
             GraphSession session = cypherEngine.openSession()) {
            List<StorageContext> contexts = List.of(
                    ImmutableStorageContext.shared("t"),
                    ImmutableStorageContext.separatedSchema("t", "s"),
                    ImmutableStorageContext.dedicated("t", "d"));
            for (StorageContext ctx : contexts) {
                ScopedValue.where(KernelProviders.STORAGE_CONTEXT, ctx).run(() ->
                        assertScopeRefused(() -> session.traversePath(onePathSpec()),
                                "Community Cypher (Neo4j)", ctx.strategy().name()));
            }

            ScopedValue.where(KernelProviders.STORAGE_CONTEXT, ImmutableStorageContext.GLOBAL).run(() ->
                    assertNotScopeRefused(() -> session.traversePath(onePathSpec())));
        }
    }

    private static GraphPathSpec onePathSpec() {
        GraphEdgeDescriptor edge = GraphEdgeDescriptor.create("Product", "LINKED", "Product");
        return new GraphPathSpec(UUID.randomUUID(), List.of(GraphHop.of(edge)));
    }

    private static GraphTraversal oneTraversal() {
        return GraphTraversal.create(UUID.randomUUID(),
                GraphEdgeDescriptor.create("Product", "LINKED", "Product"), 1);
    }

    private static void assertScopeRefused(ThrowingCallable call, String... detailParts) {
        assertThatThrownBy(call)
                .isInstanceOf(GraphQueryException.class)
                .satisfies(ex -> {
                    GraphQueryException gqe = (GraphQueryException) ex;
                    assertThat(gqe.rawArgs()[0]).isEqualTo("ISOLATION_SCOPE");
                    assertThat((String) gqe.rawArgs()[1]).contains(detailParts);
                });
    }

    private static void assertNotScopeRefused(ThrowingCallable call) {
        assertThatThrownBy(call).satisfies(ex -> {
            if (ex instanceof GraphQueryException gqe) {
                assertThat(gqe.rawArgs()[0]).isNotEqualTo("ISOLATION_SCOPE");
            }
        });
    }

    /** An engine that counts and fails every connection open, so a pass of the guard is observable. */
    private static PersistenceEngine stubEngine(AtomicInteger opened) {
        return (PersistenceEngine) Proxy.newProxyInstance(
                PersistenceEngine.class.getClassLoader(),
                new Class<?>[] {PersistenceEngine.class},
                (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "openConnection" -> {
                            opened.incrementAndGet();
                            throw new IllegalStateException("stub engine opens no connection");
                        }
                        case "hashCode" -> {
                            return System.identityHashCode(proxy);
                        }
                        case "equals" -> {
                            return proxy == args[0];
                        }
                        case "toString" -> {
                            return "stubEngine";
                        }
                        default -> throw new UnsupportedOperationException(method.getName());
                    }
                });
    }
}
