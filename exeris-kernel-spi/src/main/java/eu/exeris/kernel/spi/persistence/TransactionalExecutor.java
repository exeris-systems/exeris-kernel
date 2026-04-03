/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.spi.persistence;

import eu.exeris.kernel.spi.exceptions.persistence.PersistenceProviderException;

import java.util.Objects;
import java.util.function.Function;

/**
 * SPI: Pure contract for virtual-thread-native transaction lifecycle management.
 *
 * <h2>Why This Belongs in SPI</h2>
 * <p>The TCK Inquisition mandates that {@code AbstractTransactionalExecutorTck}
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
 * @see PersistenceEngine
 * @see PersistenceConnection
 * @since 0.5.0
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
     * <p>Implementations may optimize the {@code readOnly=true} +
     * {@link TransactionIsolation#READ_COMMITTED} combination by running the work
     * without an explicit {@code BEGIN}/{@code COMMIT} cycle (auto-commit semantics),
     * while still enforcing the no-open-transaction invariant before the call returns.
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

    /**
     * Executes a read session at {@link TransactionIsolation#READ_COMMITTED}.
     *
     * <p>Within a single read session, multiple {@link ReadSession#query(Function)}
     * calls may reuse the same underlying connection.
     *
     * @param work read-session callback; must not be {@code null}
     * @param <T>  callback result type
     * @return callback result
     */
    default <T> T inReadSession(Function<ReadSession, T> work) {
        return inReadSession(TransactionIsolation.READ_COMMITTED, work);
    }

    /**
     * Executes a read session with an explicit isolation level.
     *
     * <p>Default fallback implementation delegates to a single {@link #query(Function)}
     * call and exposes a session wrapper that executes all nested queries on the
      * same opened connection.
      *
      * <p><strong>Contract note:</strong> this default fallback does not itself establish
      * transactional boundaries or issue isolation-setting commands. Implementations that
      * require strict enforcement of the requested {@code isolation} must override this
      * method with provider-specific transactional semantics.
     *
     * @param isolation transaction isolation level; must not be {@code null}
     * @param work      read-session callback; must not be {@code null}
     * @param <T>       callback result type
     * @return callback result
     */
    default <T> T inReadSession(TransactionIsolation isolation, Function<ReadSession, T> work) {
        Objects.requireNonNull(isolation, "isolation must not be null");
        Objects.requireNonNull(work, "work must not be null");
        return query(conn -> work.apply(new ReadSession() {
            @Override
            public <R> R query(Function<PersistenceConnection, R> query) {
                return query.apply(conn);
            }
        }));
    }

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

    /**
     * Read-session view bound to one connection scope.
     */
    @FunctionalInterface
    interface ReadSession {
        /**
         * Executes a query operation using the active read-session connection.
         *
         * @param query query callback; must not be {@code null}
         * @param <T>   callback result type
         * @return callback result
         */
        <T> T query(Function<PersistenceConnection, T> query);
    }
}
