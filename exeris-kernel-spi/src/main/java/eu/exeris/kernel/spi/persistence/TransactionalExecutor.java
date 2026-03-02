/*
 * Copyright (C) 2025-2026 Exeris. All rights reserved.
 *
 * This code is part of the Exeris Systems.
 * Distributed under the proprietary Exeris Software License.
 * Unauthorized copying or distribution is prohibited.
 */
package eu.exeris.kernel.spi.persistence;

import eu.exeris.kernel.spi.exceptions.persistence.PersistenceProviderException;

import java.util.function.Function;

/**
 * SPI: Pure contract for virtual-thread-native transaction lifecycle management.
 *
 * <h2>Why This Belongs in SPI</h2>
 * <p>The TCK Inquisition mandates that {@code AbstractTransactionOrchestratorTck}
 * depends <b>only</b> on {@code exeris-kernel-spi}. A concrete class in Core
 * cannot be imported by the TCK without breaking The Wall. This interface is
 * the contract; {@code TransactionOrchestrator} in {@code exeris-kernel-core}
 * is its single canonical implementation.
 *
 * <h2>Responsibility</h2>
 * <p>Replaces legacy {@code @Transactional} AOP, {@code JdbcTransactionManager},
 * and {@code SessionTransactionManager} with a zero-ThreadLocal, pure-functional
 * API built on Virtual Threads and {@link ScopedValue}.
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * TransactionalExecutor tx = ... // injected via KernelProviders or constructor
 *
 * // Managed write — explicit bounds, auto commit/rollback, retry on 40001
 * tx.executeManaged(conn -> {
 *     conn.prepare("INSERT INTO events (id, data) VALUES ($1, $2)")
 *         .bindString(0, id).bindBytes(1, payload).executeUpdate();
 * });
 *
 * // Read — no transaction boundary, auto-closed connection
 * long count = tx.query(conn -> {
 *     try (QueryResult result = conn.executeQuery("SELECT count(*) FROM events")) {
 *         return result.row().getLong(0);
 *     }
 * });
 * }</pre>
 *
 * <h2>Retry Semantics</h2>
 * <p>PostgreSQL {@code 40001} (serialization failure) and {@code 40P01} (deadlock)
 * are retried up to the configured maximum with exponential back-off.
 *
 * <h2>ScopedValue Flow</h2>
 * <p>The {@link eu.exeris.kernel.spi.security.StorageContext} is read from
 * {@link eu.exeris.kernel.spi.context.KernelProviders#STORAGE_CONTEXT}.
 * No {@code ThreadLocal} — compliant with JEP 506.
 *
 * <h2>The Wall (Open-Core)</h2>
 * <p>This interface imports ONLY {@code exeris-kernel-spi} types.
 * Zero knowledge of HikariCP, pgjdbc, io_uring, or any Community/Enterprise class.
 *
 * @since 0.5.0
 * @see PersistenceEngine
 * @see PersistenceConnection
 */
public interface TransactionalExecutor {

    // =========================================================================
    // Core API
    // =========================================================================

    /**
     * Executes a transactional unit of work with automatic rollback and retry
     * on serialization failure ({@code 40001}) or deadlock ({@code 40P01}).
     *
     * <p>The {@code work} lambda receives an open connection and is responsible
     * for calling {@link PersistenceConnection#beginTransaction()} and
     * {@link PersistenceConnection#commit()}. On any exception the executor
     * rolls back and decides whether to retry based on the configured policy.
     *
     * @param work lambda receiving an open connection; must not be {@code null}
     * @throws PersistenceProviderException on non-retryable error or exhausted retries
     */
    void execute(TransactionalWork work);

    /**
     * Executes a managed write with automatic {@code beginTransaction()} /
     * {@code commit()} / {@code rollback()} boundaries.
     *
     * <p>The {@code work} lambda only performs data operations — no explicit
     * {@code BEGIN} or {@code COMMIT} is required.
     *
     * @param work data operations to execute; must not be {@code null}
     * @throws PersistenceProviderException on failure or exhausted retries
     */
    void executeManaged(TransactionalWork work);

    /**
     * Executes a managed write with explicit isolation level and read-only flag.
     *
     * @param isolation transaction isolation level; must not be {@code null}
     * @param readOnly  {@code true} for read-only transactions
     * @param work      data operations to execute; must not be {@code null}
     * @throws PersistenceProviderException on failure or exhausted retries
     */
    void executeManaged(TransactionIsolation isolation, boolean readOnly, TransactionalWork work);

    /**
     * Executes a read-only query and returns a result.
     *
     * <p>No transaction boundary is opened — auto-commit semantics.
     * The connection is closed when the lambda returns.
     *
     * @param query lambda that receives a connection and returns a value;
     *              must not be {@code null}
     * @param <T>   result type
     * @return result of {@code query}; may be {@code null} if the lambda returns {@code null}
     * @throws PersistenceProviderException on query failure
     */
    <T> T query(Function<PersistenceConnection, T> query);

    // =========================================================================
    // Functional interface
    // =========================================================================

    /**
     * A unit of transactional work executed within a managed connection scope.
     *
     * <p>Implementations MUST NOT close the supplied {@link PersistenceConnection} —
     * lifecycle is managed by the {@link TransactionalExecutor}.
     */
    @FunctionalInterface
    interface TransactionalWork {
        /**
         * Executes the unit of work.
         *
         * @param connection the active connection; never {@code null}
         * @throws PersistenceProviderException on domain-level persistence failures
         */
        void run(PersistenceConnection connection);
    }
}

