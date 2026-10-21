/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.graph;

import eu.exeris.kernel.community.http.CommunityHttpRequestProcessor;
import eu.exeris.kernel.community.persistence.PersistenceSessionBox;
import eu.exeris.kernel.spi.context.KernelProviders;
import eu.exeris.kernel.spi.exceptions.graph.GraphQueryException;
import eu.exeris.kernel.spi.graph.GraphSession;
import eu.exeris.kernel.spi.graph.algorithm.EdgeWeightFunction;
import eu.exeris.kernel.spi.graph.model.GraphEdgeDescriptor;
import eu.exeris.kernel.spi.graph.model.GraphHop;
import eu.exeris.kernel.spi.graph.model.GraphPathSpec;
import eu.exeris.kernel.spi.graph.model.GraphTraversal;
import eu.exeris.kernel.spi.graph.model.PathResult;
import eu.exeris.kernel.spi.memory.LoanedBuffer;
import eu.exeris.kernel.spi.persistence.PersistenceConnection;
import eu.exeris.kernel.spi.security.StorageContext;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Community-tier {@link GraphSession}: a thin facade that, at construction, picks one
 * {@link CommunityGraphBackend} — {@link CommunityGraphCypherHelper} for a Cypher dialect,
 * {@link CommunityGraphSqlHelper} otherwise — and forwards every {@link GraphSession}
 * operation to it unchanged, except
 * {@link #findShortestPath(GraphEdgeDescriptor, UUID, UUID)}, which this class implements
 * directly on top of {@link CommunityPathFinder}.
 *
 * <p><b>Allocation:</b> none of its own; {@link #streamPathJson} forwards to the backend,
 * which allocates the caller-owned {@link LoanedBuffer} it returns, and
 * {@link #findShortestPath(GraphEdgeDescriptor, UUID, UUID)} builds a fresh
 * {@link CommunityPathFinder} whenever {@code source} and {@code target} differ; the
 * {@code source.equals(target)} case returns without building one.
 * <p><b>Thread confinement:</b> not thread-safe — the {@code backend} field is set once and
 * never reassigned, but the backend it points to mutates its own transaction state (see
 * {@link CommunityGraphCypherHelper}) without synchronization; each virtual thread must
 * obtain its own session from {@link CommunityGraphEngine#openSession()}.
 * <p><b>Ownership:</b> {@link #close()} closes the backend. Buffers returned from
 * {@link #streamPathJson} are not touched by {@link #close()} — they remain the caller's
 * responsibility per {@link GraphSession#streamPathJson(GraphPathSpec)}.
 *
 * <p>Before issuing any query, traversal operations ({@link #traversePath},
 * {@link #streamPathJson}, and their single-hop delegates {@link #traverseBreadthFirst} and
 * {@link #streamBfsJson}) verify that the path length does not exceed {@link #MAX_PATH_LENGTH}
 * and that the ambient {@link StorageContext} can be scoped by the selected backend.
 * Traversals under an unscopable context are refused before a connection or driver session is
 * acquired (the scope guard covers the four traversal methods only).
 */
// SPI contract: one method per graph operation, and a guard per traversal entry point; the
// class-total complexity is the sum of small methods, none above 7.
@SuppressWarnings({"PMD.TooManyMethods", "PMD.CyclomaticComplexity"})
final class CommunityGraphSession implements GraphSession {
    /* default */ static final int MAX_PATH_LENGTH = CommunityGraphDialect.MAX_PATH_LENGTH;

    private static final String ACCESS_QUERY_TYPE = "ACCESS";
    private static final String PATH_LIMIT_QUERY_TYPE = "PATH_LIMIT";
    private static final String PATH_LIMIT_DETAIL =
            "path length bound (sum of maxRepeat) exceeds the Community graph limit of " + MAX_PATH_LENGTH;
    private static final String SCOPE_QUERY_TYPE = "ISOLATION_SCOPE";
    private static final String NO_ENGINE_SCOPE_DETAIL =
            "a graph query under a tenant context needs a bound PersistenceEngine to open a scoped "
                    + "connection; without one the request session serves an unscoped connection";
    private static final String SHORTEST_PATH_ALGORITHM = "dijkstra";

    private final CommunityGraphBackend backend;

    /* default */ CommunityGraphSession(CommunityGraphDialect dialect, CommunityNeo4jClient neo4jClient,
                                        List<GraphEdgeDescriptor> registeredEdges) {
        Objects.requireNonNull(dialect, "dialect must not be null");
        Objects.requireNonNull(registeredEdges, "registeredEdges must not be null");
        this.backend = dialect.isCypherMode()
                ? new CommunityGraphCypherHelper(dialect, neo4jClient)
                : new CommunityGraphSqlHelper(dialect, this::acquireConnection, registeredEdges);
    }

    @Override
    public List<UUID> traversePath(GraphPathSpec path) {
        requireWithinLimit(path);
        requireScopable();
        return backend.traversePath(path);
    }

    @Override
    public LoanedBuffer streamPathJson(GraphPathSpec path) {
        requireWithinLimit(path);
        requireScopable();
        return backend.streamPathJson(path);
    }

    @Override
    public List<UUID> traverseBreadthFirst(GraphTraversal traversal) {
        return traversePath(asPath(traversal));
    }

    @Override
    public LoanedBuffer streamBfsJson(GraphTraversal traversal) {
        return streamPathJson(asPath(traversal));
    }

    @Override
    public void createEdge(GraphEdgeDescriptor edge, UUID sourceId, UUID targetId,
                           double weight, String properties) {
        backend.createEdge(edge, sourceId, targetId, weight, properties);
    }

    @Override
    public void upsertEdge(GraphEdgeDescriptor edge, UUID sourceId, UUID targetId,
                           double weight, String properties) {
        backend.upsertEdge(edge, sourceId, targetId, weight, properties);
    }

    @Override
    public void deleteEdge(GraphEdgeDescriptor edge, UUID sourceId, UUID targetId) {
        backend.deleteEdge(edge, sourceId, targetId);
    }

    @Override
    public void upsertNode(String label, UUID nodeId, LoanedBuffer properties) {
        backend.upsertNode(label, nodeId, properties);
    }

    @Override
    public void deleteNode(String label, UUID nodeId) {
        backend.deleteNode(label, nodeId);
    }

    @Override
    public UUID getRootNode() {
        return backend.getRootNode();
    }

    /**
     * For {@code source} equal to {@code target}, returns a zero-cost single-node path with
     * no adjacency lookup; otherwise loads the full adjacency for {@code edge}'s relationship
     * type from the backend into a fresh {@link CommunityPathFinder} and runs Dijkstra
     * against it via {@link EdgeWeightFunction#DEFAULT} — nothing is cached between
     * invocations of that path.
     *
     * @param edge   relationship type to restrict the search to
     * @param source source node ID
     * @param target target node ID
     * @return a zero-cost single-node path when {@code source} equals {@code target};
     *         otherwise the path {@link CommunityPathFinder} found, or
     *         {@link PathResult#notFound} when none exists
     * @throws NullPointerException if {@code edge}, {@code source}, or {@code target} is
     *                               {@code null}
     * @throws eu.exeris.kernel.spi.exceptions.graph.GraphQueryException ({@code EX-GRPH-5002})
     *         if loading adjacency from the backend fails
     */
    @Override
    public PathResult findShortestPath(GraphEdgeDescriptor edge, UUID source, UUID target) {
        Objects.requireNonNull(edge, "edge must not be null");
        Objects.requireNonNull(source, "source must not be null");
        Objects.requireNonNull(target, "target must not be null");

        if (source.equals(target)) {
            return new PathResult(source, target, List.of(source), 0.0, 0, SHORTEST_PATH_ALGORITHM);
        }

        CommunityPathFinder.Builder builder = CommunityPathFinder.builder();
        backend.loadAdjacency(edge, builder);
        return builder.build().findShortestPath(source, target, EdgeWeightFunction.DEFAULT);
    }

    @Override
    public void beginTransaction() {
        backend.beginTransaction();
    }

    @Override
    public void commit() {
        backend.commit();
    }

    @Override
    public void rollback() {
        backend.rollback();
    }

    @Override
    public void close() {
        backend.close();
    }

    private PersistenceConnection acquireConnection() {
        PersistenceSessionBox box = CommunityHttpRequestProcessor.REQUEST_SESSION.isBound()
                ? CommunityHttpRequestProcessor.REQUEST_SESSION.get()
                : null;
        // Engine first, deliberately. It consults this same request session, but keys it from the
        // ambient StorageContext - the one source the engine now derives every scope key from.
        // Asking the box directly keyed the session "shared" regardless of context, which is half
        // of the BYPASS_SCOPE_MISMATCH collision; and when this call arrived first it left the
        // request's session connection with no ConnectionInterceptor run on it for its whole life.
        if (KernelProviders.PERSISTENCE_ENGINE.isBound()) {
            return KernelProviders.PERSISTENCE_ENGINE.get()
                    .openConnection(KernelProviders.storageContextOrSystem());
        }
        // Fallback for a scope carrying the session but not the engine: the box holds its own
        // engine reference and can still serve the request, on a connection that is not scoped
        // to the ambient StorageContext. requireScopable refuses tenant traversals before they
        // reach this point; system-scope calls and writes are served from it.
        if (box != null) {
            PersistenceConnection connection = box.requestScopedConnection();
            if (connection != null) {
                return connection;
            }
        }
        throw new GraphQueryException(ACCESS_QUERY_TYPE,
                "PersistenceEngine is not available in this request scope (no ScopedValue binding)");
    }

    private static GraphPathSpec asPath(GraphTraversal traversal) {
        Objects.requireNonNull(traversal, "traversal must not be null");
        return new GraphPathSpec(traversal.startNodeId(),
                List.of(new GraphHop(traversal.edgeDescriptor(), 1, traversal.maxDepth())));
    }

    private static void requireWithinLimit(GraphPathSpec path) {
        Objects.requireNonNull(path, "path must not be null");
        long sum = 0;
        for (GraphHop hop : path.hops()) {
            sum += hop.maxRepeat();
            if (sum > MAX_PATH_LENGTH) {
                throw new GraphQueryException(PATH_LIMIT_QUERY_TYPE, PATH_LIMIT_DETAIL);
            }
        }
    }

    private void requireScopable() {
        StorageContext ctx = KernelProviders.storageContextOrSystem();
        if (ctx.strategy() == StorageContext.IsolationStrategy.SHARED && ctx.isolationKey().isEmpty()) {
            return;
        }
        if (!backend.scopableStrategies().contains(ctx.strategy())) {
            throw new GraphQueryException(SCOPE_QUERY_TYPE, backend.scopeRefusalDetail(ctx.strategy()));
        }
        // Only the engine opens a connection under the ambient context; the request-session
        // fallback in acquireConnection hands out a pooled connection no interceptor has scoped.
        if (!KernelProviders.PERSISTENCE_ENGINE.isBound()) {
            throw new GraphQueryException(SCOPE_QUERY_TYPE, NO_ENGINE_SCOPE_DETAIL);
        }
    }
}
