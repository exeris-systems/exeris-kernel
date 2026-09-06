/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.graph;

import eu.exeris.kernel.spi.exceptions.graph.GraphQueryException;
import eu.exeris.kernel.spi.graph.model.GraphEdgeDescriptor;
import eu.exeris.kernel.spi.graph.model.GraphTraversal;
import eu.exeris.kernel.spi.memory.LoanedBuffer;
import org.neo4j.driver.Result;
import org.neo4j.driver.Session;
import org.neo4j.driver.Transaction;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;

/**
 * Cypher backend orchestrator (QA-008). Owns the Neo4j session/transaction lifecycle and
 * dispatches every {@link CommunityGraphBackend} call to a {@link CommunityGraphCypherReader}
 * (read-only) or a {@link CommunityGraphCypherWriter} (mutations). The reader and writer
 * share this orchestrator as their {@link CypherExecutor}, so transactional cohesion is
 * preserved across reads and writes against a single session.
 *
 * <p><b>Allocation:</b> opens a Neo4j driver {@code Session} lazily, either from
 * {@link #beginTransaction()} or from the first {@link #executeRead} / {@link #executeWrite}
 * call made with no transaction active; allocates a {@code Transaction} only from
 * {@link #beginTransaction()}.
 * <p><b>Thread confinement:</b> owner thread — {@code cypherSession} and
 * {@code cypherTransaction} are plain fields mutated by every lifecycle method without
 * synchronization; concurrent calls from more than one thread corrupt that state.
 * <p><b>Ownership:</b> holds the session/transaction it opens and releases both from
 * {@link #commit()}, {@link #rollback()}, or {@link #close()} — each is a no-op when no
 * transaction is active. Does not own the {@link CommunityNeo4jClient} passed to its
 * constructor; that client outlives this instance and is closed by
 * {@link CommunityGraphEngine}.
 *
 * @since 0.7
 */
// CommunityGraphBackend is wide; orchestrator implements every method via delegation
// to Reader/Writer or via direct transaction-lifecycle handling, so the per-method
// branching is uniformly small (highest method CC = 5) but accumulates across the
// 13-method backend surface.
@SuppressWarnings({"PMD.TooManyMethods", "PMD.CyclomaticComplexity"})
final class CommunityGraphCypherHelper implements CommunityGraphBackend, CypherExecutor {

    private static final String PMD_AVOID_CATCHING_GENERIC_EXCEPTION = "PMD.AvoidCatchingGenericException";
    private static final String CYPHER_BACKEND_QUERY_TYPE = "CYPHER_BACKEND";

    private final CommunityNeo4jClient neo4jClient;
    private final CommunityGraphCypherReader reader;
    private final CommunityGraphCypherWriter writer;
    private Session cypherSession;
    private Transaction cypherTransaction;

    /* default */ CommunityGraphCypherHelper(CommunityGraphDialect dialect, CommunityNeo4jClient neo4jClient) {
        Objects.requireNonNull(dialect, "dialect must not be null");
        this.neo4jClient = neo4jClient;
        this.reader = new CommunityGraphCypherReader(dialect, this);
        this.writer = new CommunityGraphCypherWriter(this);
    }

    // ─── CommunityGraphBackend — read delegation ─────────────────────────────────

    @Override
    public List<UUID> traverseBreadthFirst(GraphTraversal traversal) {
        return reader.traverseBreadthFirst(traversal);
    }

    @Override
    public LoanedBuffer streamBfsJson(GraphTraversal traversal) {
        return reader.streamBfsJson(traversal);
    }

    @Override
    public UUID getRootNode() {
        return reader.getRootNode();
    }

    @Override
    public void loadAdjacency(GraphEdgeDescriptor edge, CommunityPathFinder.Builder builder) {
        reader.loadAdjacency(edge, builder);
    }

    // ─── CommunityGraphBackend — write delegation ────────────────────────────────

    @Override
    public void createEdge(GraphEdgeDescriptor edge, UUID sourceId, UUID targetId,
                    double weight, String properties) {
        writer.createEdge(edge, sourceId, targetId, weight, properties);
    }

    @Override
    public void upsertEdge(GraphEdgeDescriptor edge, UUID sourceId, UUID targetId,
                    double weight, String properties) {
        writer.upsertEdge(edge, sourceId, targetId, weight, properties);
    }

    @Override
    public void deleteEdge(GraphEdgeDescriptor edge, UUID sourceId, UUID targetId) {
        writer.deleteEdge(edge, sourceId, targetId);
    }

    @Override
    public void upsertNode(String label, UUID nodeId, LoanedBuffer properties) {
        writer.upsertNode(label, nodeId, properties);
    }

    @Override
    public void deleteNode(String label, UUID nodeId) {
        writer.deleteNode(label, nodeId);
    }

    // ─── CommunityGraphBackend — transaction lifecycle ───────────────────────────

    /**
     * Opens a Neo4j session and begins a transaction on it. A no-op if a transaction is
     * already active.
     *
     * @throws eu.exeris.kernel.spi.exceptions.graph.GraphQueryException ({@code EX-GRPH-5002})
     *         if opening the session or beginning the transaction fails; the partially
     *         opened session, if any, is closed before this throws
     */
    @Override
    @SuppressWarnings({PMD_AVOID_CATCHING_GENERIC_EXCEPTION, "PMD.NullAssignment"})
    public void beginTransaction() {
        if (cypherTransaction != null) {
            return;
        }
        try {
            cypherSession = openCypherSession();
            cypherTransaction = cypherSession.beginTransaction();
        } catch (RuntimeException cause) {
            closeSessionQuietly(cypherSession);
            cypherSession = null;
            cypherTransaction = null;
            throw new GraphQueryException(CYPHER_BACKEND_QUERY_TYPE, "Failed to begin Cypher transaction", cause);
        }
    }

    /**
     * Commits the active transaction and releases the session it ran on. A no-op if no
     * transaction is active.
     *
     * @throws eu.exeris.kernel.spi.exceptions.graph.GraphQueryException ({@code EX-GRPH-5002})
     *         if the commit fails; the session and transaction are released either way
     */
    @Override
    @SuppressWarnings(PMD_AVOID_CATCHING_GENERIC_EXCEPTION)
    public void commit() {
        if (cypherTransaction == null) {
            return;
        }
        try {
            cypherTransaction.commit();
        } catch (RuntimeException cause) {
            throw new GraphQueryException(CYPHER_BACKEND_QUERY_TYPE, "Failed to commit Cypher transaction", cause);
        } finally {
            closeCypherTransactionResources();
        }
    }

    /**
     * Rolls back the active transaction and releases the session it ran on. A no-op if no
     * transaction is active.
     *
     * @throws eu.exeris.kernel.spi.exceptions.graph.GraphQueryException ({@code EX-GRPH-5002})
     *         if the rollback fails; the session and transaction are released either way
     */
    @Override
    @SuppressWarnings(PMD_AVOID_CATCHING_GENERIC_EXCEPTION)
    public void rollback() {
        if (cypherTransaction == null) {
            return;
        }
        try {
            cypherTransaction.rollback();
        } catch (RuntimeException cause) {
            throw new GraphQueryException(CYPHER_BACKEND_QUERY_TYPE, "Failed to rollback Cypher transaction", cause);
        } finally {
            closeCypherTransactionResources();
        }
    }

    /**
     * Rolls back an active transaction and releases the session it ran on. A no-op if no
     * transaction is active.
     *
     * @throws eu.exeris.kernel.spi.exceptions.graph.GraphQueryException ({@code EX-GRPH-5002})
     *         if the rollback fails; the session and transaction are released either way
     */
    @Override
    @SuppressWarnings({PMD_AVOID_CATCHING_GENERIC_EXCEPTION, "PMD.NullAssignment"})
    public void close() {
        if (cypherTransaction != null) {
            try {
                cypherTransaction.rollback();
            } catch (RuntimeException cause) {
                throw new GraphQueryException(CYPHER_BACKEND_QUERY_TYPE,
                        "Failed to rollback active Cypher transaction during close", cause);
            } finally {
                closeCypherTransactionResources();
            }
            return;
        }
        if (cypherSession != null) {
            try {
                cypherSession.close();
            } finally {
                cypherSession = null;
            }
        }
    }

    // ─── CypherExecutor — session/transaction-aware run ──────────────────────────

    /**
     * {@inheritDoc}
     *
     * <p>Runs inside the active transaction when one is open; otherwise opens and closes a
     * session scoped to this call. Unlike {@link #executeWrite}, does not translate a
     * failure into {@link eu.exeris.kernel.spi.exceptions.graph.GraphQueryException} — a
     * Neo4j driver exception propagates to the caller unchanged, which is why every
     * {@link CommunityGraphCypherReader} call site wraps its own {@code executeRead} call.
     */
    @Override
    public <T> T executeRead(String query, Map<String, Object> params, Function<Result, T> readerFn) {
        if (cypherTransaction != null) {
            return readerFn.apply(cypherTransaction.run(query, params));
        }
        try (Session session = openCypherSession()) {
            return readerFn.apply(session.run(query, params));
        }
    }

    /**
     * {@inheritDoc}
     *
     * <p>Runs inside the active transaction when one is open; otherwise opens and closes a
     * session scoped to this call.
     *
     * @throws eu.exeris.kernel.spi.exceptions.graph.GraphQueryException ({@code EX-GRPH-5002})
     *         if the write fails
     */
    @Override
    @SuppressWarnings(PMD_AVOID_CATCHING_GENERIC_EXCEPTION)
    public void executeWrite(String query, Map<String, Object> params) {
        try {
            if (cypherTransaction != null) {
                cypherTransaction.run(query, params).consume();
                return;
            }
            try (Session session = openCypherSession()) {
                session.run(query, params).consume();
            }
        } catch (RuntimeException cause) {
            throw new GraphQueryException(CYPHER_BACKEND_QUERY_TYPE,
                    CommunityGraphCypherReader.CYPHER_EXECUTION_DETAIL, cause);
        }
    }

    // ─── Internals ───────────────────────────────────────────────────────────────

    private Session openCypherSession() {
        return requireNeo4jClient().openSession();
    }

    private CommunityNeo4jClient requireNeo4jClient() {
        if (neo4jClient == null) {
            throw new GraphQueryException(CYPHER_BACKEND_QUERY_TYPE,
                    "Neo4j client is not configured for Cypher backend");
        }
        return neo4jClient;
    }

    @SuppressWarnings("PMD.NullAssignment")
    private void closeCypherTransactionResources() {
        closeTransactionQuietly(cypherTransaction);
        cypherTransaction = null;
        closeSessionQuietly(cypherSession);
        cypherSession = null;
    }

    private static void closeTransactionQuietly(Transaction transaction) {
        if (transaction != null) {
            transaction.close();
        }
    }

    private static void closeSessionQuietly(Session session) {
        if (session != null) {
            session.close();
        }
    }
}
