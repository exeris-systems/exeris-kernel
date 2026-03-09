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
        "PMD.AvoidCatchingGenericException"
})
public final class TransactionOrchestrator implements TransactionalExecutor {

    // =========================================================================
    // Instance fields
    // =========================================================================

    private final PersistenceEngine    engine;
    private final TransactionRetryPolicy retryPolicy;

    // =========================================================================
    // Constructors
    // =========================================================================

    /**
     * Creates an orchestrator bound to the given engine and retry policy.
     */
    public TransactionOrchestrator(PersistenceEngine engine, TransactionRetryPolicy retryPolicy) {
        this.engine      = Objects.requireNonNull(engine,      "engine must not be null");
        this.retryPolicy = Objects.requireNonNull(retryPolicy, "retryPolicy must not be null");
    }

    /** Convenience constructor with {@link TransactionRetryPolicy#NONE}. */
    public TransactionOrchestrator(PersistenceEngine engine) {
        this(engine, TransactionRetryPolicy.NONE);
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
        Objects.requireNonNull(work, "work must not be null");
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
                    return; // success
                }
                // retryable error — loop continues if attempts remain
            }
        }

        // All retries exhausted — this path is now always reachable
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
        Objects.requireNonNull(work,      "work must not be null");
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
                        // loop continues if attempts remain
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
        StorageContext ctx = resolveStorageContext();
        try (PersistenceConnection conn = engine.openConnection(ctx)) {
            return query.apply(conn);
        }
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

    private static StorageContext resolveStorageContext() {
        if (KernelProviders.STORAGE_CONTEXT.isBound()) {
            return KernelProviders.STORAGE_CONTEXT.get();
        }
        return ImmutableStorageContext.system();
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

    private static boolean isRetryable(PersistenceProviderException ppe) {
        Object[] args = ppe.rawArgs();
        if (args != null && args.length > 0 && args[0] instanceof String sqlState) {
            return PersistenceErrorTranslator.isRetryable(sqlState);
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
