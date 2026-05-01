/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.core.persistence;

import eu.exeris.kernel.spi.context.KernelProviders;
import eu.exeris.kernel.spi.exceptions.persistence.PersistenceProviderException;
import eu.exeris.kernel.spi.persistence.PersistenceConnection;
import eu.exeris.kernel.spi.persistence.PersistenceEngine;
import eu.exeris.kernel.spi.persistence.TransactionIsolation;
import eu.exeris.kernel.spi.persistence.TransactionalExecutor;
import eu.exeris.kernel.spi.security.ImmutableStorageContext;
import eu.exeris.kernel.spi.security.StorageContext;

import java.util.Objects;
import java.util.function.Function;

/**
 * Core: Virtual-Thread-native transaction lifecycle manager.
 *
 * <h2>Responsibility (The Brain)</h2>
 * <p>Canonical implementation of {@link TransactionalExecutor} (SPI). Replaces the
 * legacy {@code @Transactional} AOP pattern and thread-bound
 * {@code JdbcTransactionManager} / {@code SessionTransactionManager} with a
 * zero-ThreadLocal, pure-functional API built on Virtual Threads and {@link ScopedValue}.
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * TransactionOrchestrator tx = new TransactionOrchestrator(
 *     KernelProviders.PERSISTENCE_ENGINE.get(),
 *     TransactionRetryPolicy.exponential(3, 50L));
 *
 * // Managed write — explicit bounds, auto commit/rollback
 * tx.executeManaged(conn -> {
 *     conn.prepare("INSERT INTO events (id, data) VALUES ($1, $2)")
 *         .bindString(0, id).bindBytes(1, payload).executeUpdate();
 * });
 *
 * // Read — no transaction, auto-closed connection
 * long count = tx.query(conn ->
 *     conn.executeQuery("SELECT count(*) FROM events")
 *         .row().getLong(0));
 * }</pre>
 *
 * <h2>Retry Semantics</h2>
 * <p>PostgreSQL {@code 40001} (serialization failure) and {@code 40P01} (deadlock)
 * are retried up to {@link TransactionRetryPolicy#maxAttempts()} times with
 * exponential back-off. The retry loop applies to both {@link #execute} and
 * {@link #executeManaged} variants.
 *
 * <h2>ScopedValue Flow</h2>
 * <p>The {@link StorageContext} is read from {@link KernelProviders#STORAGE_CONTEXT}.
 * No {@code ThreadLocal} — compliant with JEP 506.
 *
 * <h2>The Wall (Open-Core)</h2>
 * <p>Imports only {@code exeris-kernel-spi}. Zero knowledge of HikariCP, pgjdbc,
 * io_uring, or any Community/Enterprise class.
 *
 * @since 0.5.0
 */
@SuppressWarnings({
        "PMD.CyclomaticComplexity",
        "PMD.AvoidCatchingGenericException",
        "PMD.TooManyMethods"
})
public final class TransactionOrchestrator implements TransactionalExecutor {

    private static final String WORK_NOT_NULL = "work must not be null";

    private final ScopedValue<PersistenceConnection> activeReadSessionConnection =
            ScopedValue.newInstance();

    // =========================================================================
    // Instance fields
    // =========================================================================

    private final PersistenceEngine      engine;
    private final TransactionRetryPolicy retryPolicy;
    private final StorageContext         systemContext;

    // =========================================================================
    // Constructors
    // =========================================================================

    /**
     * Creates an orchestrator with an explicit system-scope fallback context.
     *
     * <p>Use this constructor in bootstrap or maintenance tasks that run outside
     * a tenant request scope and require a specific non-default {@link StorageContext}.
     *
     * @param engine        persistence engine; must not be {@code null}
     * @param retryPolicy   retry policy; must not be {@code null}
     * @param systemContext fallback context when {@link KernelProviders#STORAGE_CONTEXT}
     *                      is unbound; must not be {@code null}
     */
    public TransactionOrchestrator(PersistenceEngine engine,
                                    TransactionRetryPolicy retryPolicy,
                                    StorageContext systemContext) {
        this.engine        = Objects.requireNonNull(engine,        "engine must not be null");
        this.retryPolicy   = Objects.requireNonNull(retryPolicy,   "retryPolicy must not be null");
        this.systemContext = Objects.requireNonNull(systemContext,  "systemContext must not be null");
    }

    /**
     * Creates an orchestrator bound to the given engine and retry policy.
     *
     * <p>Falls back to {@link ImmutableStorageContext#GLOBAL} (system scope, no tenant
     * isolation) when {@link KernelProviders#STORAGE_CONTEXT} is unbound. Use the
     * three-argument constructor if a different fallback is required.
     */
    public TransactionOrchestrator(PersistenceEngine engine, TransactionRetryPolicy retryPolicy) {
        this(engine, retryPolicy, ImmutableStorageContext.GLOBAL);
    }

    /** Convenience constructor with {@link TransactionRetryPolicy#NONE}. */
    public TransactionOrchestrator(PersistenceEngine engine) {
        this(engine, TransactionRetryPolicy.NONE, ImmutableStorageContext.GLOBAL);
    }

    // =========================================================================
    // TransactionalExecutor — SPI implementation
    // =========================================================================

    /**
     * {@inheritDoc}
     *
     * <p>Retries on {@code 40001} / {@code 40P01} per the configured {@link TransactionRetryPolicy}.
     * The {@code work} lambda is responsible for calling {@code beginTransaction()}
     * and {@code commit()} explicitly.
     */
    @Override
    public void execute(TransactionalWork work) {
        Objects.requireNonNull(work, WORK_NOT_NULL);
        StorageContext ctx = resolveStorageContext();
        int attemptIndex = 0;
        PersistenceProviderException lastError = null;

        while (attemptIndex < retryPolicy.maxAttempts()) {
            if (attemptIndex > 0) {
                sleepBackoff(retryPolicy.delayFor(attemptIndex));
            }
            int attemptNumber = attemptIndex + 1;
            attemptIndex++;

            try (PersistenceConnection conn = engine.openConnection(ctx)) {
                lastError = attemptWork(conn, work, attemptNumber);
                if (lastError == null) {
                    return;
                }
            }
        }

        TransactionLifecycleEvent.recordRetryExhausted(attemptIndex);
        if (lastError == null) {
            throw new IllegalStateException(
                    "Retry loop exhausted without recording a PersistenceProviderException");
        }
        throw lastError;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Wraps the work in a managed {@link TransactionIsolation#READ_COMMITTED}
     * transaction with {@code beginTransaction()} / {@code commit()} / {@code rollback()}.
     * Retries on {@code 40001} / {@code 40P01} per the configured
     * {@link TransactionRetryPolicy}.
     */
    @Override
    public void executeManaged(TransactionalWork work) {
        executeManaged(TransactionIsolation.READ_COMMITTED, false, work);
    }

    /** {@inheritDoc} */
    @Override
    public void executeManaged(TransactionIsolation isolation,
                                boolean readOnly,
                                TransactionalWork work) {
        Objects.requireNonNull(isolation, "isolation must not be null");
        Objects.requireNonNull(work,      WORK_NOT_NULL);

        StorageContext ctx = resolveStorageContext();
        int attemptIndex = 0;
        PersistenceProviderException lastError = null;

        while (attemptIndex < retryPolicy.maxAttempts()) {
            if (attemptIndex > 0) {
                sleepBackoff(retryPolicy.delayFor(attemptIndex));
            }
            int attemptNumber = attemptIndex + 1;
            attemptIndex++;

            try (PersistenceConnection conn = engine.openConnection(ctx)) {
                try {
                    conn.beginTransaction(isolation, readOnly);
                    TransactionLifecycleEvent.recordBegin(attemptNumber);
                    long startNs = System.nanoTime();
                    work.run(conn);
                    long durationNs = System.nanoTime() - startNs;
                    conn.commit();
                    TransactionLifecycleEvent.recordCommit(attemptNumber, durationNs);
                    return;
                } catch (PersistenceProviderException ppe) {
                    safeRollback(conn, attemptNumber);
                    if (isRetryable(ppe)) {
                        lastError = ppe;
                    } else {
                        throw ppe;
                    }
                } catch (RuntimeException rte) {
                    safeRollback(conn, attemptNumber);
                    throw rte;
                }
            }
        }

        TransactionLifecycleEvent.recordRetryExhausted(attemptIndex);
        if (lastError == null) {
            throw new IllegalStateException(
                    "Managed retry loop exhausted without recording a PersistenceProviderException");
        }
        throw lastError;
    }

    /** {@inheritDoc} */
    @Override
    public <T> T query(Function<PersistenceConnection, T> query) {
        Objects.requireNonNull(query, "query must not be null");
        if (activeReadSessionConnection.isBound()) {
            return query.apply(activeReadSessionConnection.get());
        }
        StorageContext ctx = resolveStorageContext();
        try (PersistenceConnection conn = engine.openConnection(ctx)) {
            return query.apply(conn);
        }
    }

    /** {@inheritDoc} */
    @Override
    public <T> T inReadSession(Function<ReadSession, T> work) {
        return inReadSession(TransactionIsolation.READ_COMMITTED, work);
    }

    /** {@inheritDoc} */
    @Override
    public <T> T inReadSession(TransactionIsolation isolation, Function<ReadSession, T> work) {
        Objects.requireNonNull(isolation, "isolation must not be null");
        Objects.requireNonNull(work, WORK_NOT_NULL);

        StorageContext ctx = resolveStorageContext();
        int attemptIndex = 0;
        PersistenceProviderException lastError = null;

        while (attemptIndex < retryPolicy.maxAttempts()) {
            if (attemptIndex > 0) {
                sleepBackoff(retryPolicy.delayFor(attemptIndex));
            }
            int attemptNumber = attemptIndex + 1;
            attemptIndex++;

            try (PersistenceConnection conn = engine.openConnection(ctx)) {
                try {
                    long startNs = System.nanoTime();
                    T result = isolation == TransactionIsolation.READ_COMMITTED
                            ? runReadSessionReadCommitted(conn, work, attemptNumber)
                            : runReadSessionIsolated(conn, isolation, work, attemptNumber);
                    long durationNs = System.nanoTime() - startNs;
                    TransactionLifecycleEvent.recordWorkComplete(attemptNumber, durationNs);
                    return result;
                } catch (PersistenceProviderException ppe) {
                    if (isRetryable(ppe)) {
                        lastError = ppe;
                    } else {
                        throw ppe;
                    }
                }
            }
        }

        TransactionLifecycleEvent.recordRetryExhausted(attemptIndex);
        if (lastError == null) {
            throw new IllegalStateException(
                    "Read-session retry loop exhausted without recording a PersistenceProviderException");
        }
        throw lastError;
    }

    // =========================================================================
    // Private helpers
    // =========================================================================

    /**
     * Attempts one execution of {@code work}. Returns {@code null} on success,
     * or the {@link PersistenceProviderException} if retryable — rethrows if not.
     */
    private PersistenceProviderException attemptWork(PersistenceConnection conn,
                                                      TransactionalWork work,
                                                      int attempt) {
        long startNs = System.nanoTime();
        try {
            work.run(conn);
        } catch (PersistenceProviderException ppe) {
            safeRollback(conn, attempt);
            if (isRetryable(ppe)) {
                return ppe;
            }
            throw ppe;
        } catch (RuntimeException unexpected) {
            safeRollback(conn, attempt);
            throw unexpected;
        }

        if (conn.inTransaction()) {
            safeRollback(conn, attempt);
            throw PersistenceProviderException.queryFailed(
                    "2D000",
                    "execute() work lambda returned without committing the transaction",
                    null);
        }

        long durationNs = System.nanoTime() - startNs;
        TransactionLifecycleEvent.recordWorkComplete(attempt, durationNs);
        return null;
    }

    private <T> T runReadSessionReadCommitted(PersistenceConnection conn,
                                               Function<ReadSession, T> work,
                                               int attempt) {
        T result = ScopedValue.where(activeReadSessionConnection, conn)
                .call(() -> work.apply(readSession(conn)));

        if (conn.inTransaction()) {
            safeRollback(conn, attempt);
            throw PersistenceProviderException.queryFailed(
                    "2D000",
                    "inReadSession(READ_COMMITTED) work lambda returned with an open transaction",
                    null);
        }
        return result;
    }

    private <T> T runReadSessionIsolated(PersistenceConnection conn,
                                          TransactionIsolation isolation,
                                          Function<ReadSession, T> work,
                                          int attempt) {
        try {
            conn.beginTransaction(isolation, true);
            TransactionLifecycleEvent.recordBegin(attempt);
            long startNs = System.nanoTime();
            T result = ScopedValue.where(activeReadSessionConnection, conn)
                    .call(() -> work.apply(readSession(conn)));
            long durationNs = System.nanoTime() - startNs;
            if (conn.inTransaction()) {
                conn.commit();
                TransactionLifecycleEvent.recordCommit(attempt, durationNs);
            }
            return result;
        } catch (RuntimeException ex) {
            safeRollback(conn, attempt);
            throw ex;
        }
    }

    private static ReadSession readSession(PersistenceConnection conn) {
        return new ReadSession() {
            @Override
            public <R> R query(Function<PersistenceConnection, R> query) {
                return query.apply(conn);
            }
        };
    }

    private StorageContext resolveStorageContext() {
        return KernelProviders.STORAGE_CONTEXT.isBound()
                ? KernelProviders.STORAGE_CONTEXT.get()
                : systemContext;
    }

    private static void safeRollback(PersistenceConnection conn, int attempt) {
        try {
            if (conn.inTransaction()) {
                conn.rollback();
                TransactionLifecycleEvent.recordRollback(attempt);
            }
        } catch (RuntimeException _) {
            // Ignored
        }
    }

    /**
     * SPI-contract predicate: only {@code 40001} (serialization failure) and
     * {@code 40P01} (deadlock detected) are retried by this orchestrator.
     *
     * <p>Note: {@link PersistenceErrorTranslator#isRetryable(String)} is intentionally
     * broader (covers the entire class {@code 40}) for the purpose of adding
     * {@code [RETRYABLE]} hints to translated exception messages. This method is
     * the authoritative gate for the retry loop and must not be widened beyond
     * the two codes specified in the {@link TransactionalExecutor} SPI contract.
     */
    private static boolean isRetryable(PersistenceProviderException ppe) {
        Object[] args = ppe.rawArgs();
        if (args != null && args.length > 0 && args[0] instanceof String sqlState) {
            return "40001".equals(sqlState) || "40P01".equals(sqlState);
        }
        return false;
    }

    private static void sleepBackoff(long delayMs) {
        if (delayMs <= 0) {
            return;
        }
        try {
            Thread.sleep(delayMs);
        } catch (InterruptedException interruptedEx) {
            Thread.currentThread().interrupt();
            throw PersistenceProviderException.queryFailed(
                    "57014", "Transaction retry interrupted during back-off", interruptedEx);
        }
    }
}
