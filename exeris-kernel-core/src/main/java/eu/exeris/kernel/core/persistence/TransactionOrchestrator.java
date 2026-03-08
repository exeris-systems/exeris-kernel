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
 *     RetryPolicy.exponential(3, 50L));
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
 * are retried up to {@code RetryPolicy.maxAttempts()} times with exponential back-off.
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

    private final PersistenceEngine         engine;
    private final RetryPolicy               retryPolicy;
    private final TransactionLifecycleEvent lifecycleEvent;

    // =========================================================================
    // Retry policy (Core-only, not in SPI — callers use named constants here)
    // =========================================================================

    /**
     * Valhalla-ready retry policy.
     * No identity operations — scalarizes via JIT Escape Analysis on hot path.
     *
     * @param maxAttempts       maximum total attempts (1 = no retry)
     * @param baseDelayMs       initial back-off delay in milliseconds
     * @param backoffMultiplier exponential multiplier applied after each retry
     */
    public record RetryPolicy(int maxAttempts, long baseDelayMs, double backoffMultiplier) {

        /** Default: 1 attempt, no retry. */
        public static final RetryPolicy NONE = new RetryPolicy(1, 0L, 1.0);

        /** Exponential back-off starting at {@code baseDelayMs}, doubling on each retry. */
        public static RetryPolicy exponential(int maxAttempts, long baseDelayMs) {
            return new RetryPolicy(maxAttempts, baseDelayMs, 2.0);
        }

        /** Computes delay for attempt {@code attempt} (0-indexed). */
        public long delayFor(int attempt) {
            if (attempt == 0 || baseDelayMs <= 0) {
                return 0L;
            }
            double delay = baseDelayMs * Math.pow(backoffMultiplier, attempt - 1.0);
            return (long) Math.min(delay, 30_000.0);
        }
    }

    // =========================================================================
    // Constructors
    // =========================================================================

    /**
     * Creates an orchestrator bound to the given engine.
     */
    public TransactionOrchestrator(PersistenceEngine engine, RetryPolicy retryPolicy) {
        this.engine         = engine;
        this.retryPolicy    = retryPolicy;
        this.lifecycleEvent = new TransactionLifecycleEvent();
    }

    /** Convenience constructor with {@link RetryPolicy#NONE}. */
    public TransactionOrchestrator(PersistenceEngine engine) {
        this(engine, RetryPolicy.NONE);
    }

    // =========================================================================
    // TransactionalExecutor — SPI implementation
    // =========================================================================

    /**
     * {@inheritDoc}
     *
     * <p>Retries on {@code 40001} / {@code 40P01} per the configured {@link RetryPolicy}.
     * The {@code work} lambda is responsible for calling {@code beginTransaction()}
     * and {@code commit()} explicitly.
     */
    @Override
    public void execute(TransactionalWork work) {
        StorageContext ctx = resolveStorageContext();
        int attempt = 0;
        PersistenceProviderException lastError = null;

        while (attempt < retryPolicy.maxAttempts()) {
            if (attempt > 0) {
                sleepBackoff(retryPolicy.delayFor(attempt));
            }
            attempt++;

            try (PersistenceConnection conn = engine.openConnection(ctx)) {
                lastError = attemptWork(conn, work, attempt);
                if (lastError == null) {
                    return; // success
                }
                // retryable error — loop continues
            }
        }

        // All retries exhausted
        lifecycleEvent.recordRetryExhausted(attempt);
        throw lastError != null ? lastError
                : PersistenceProviderException.queryFailed(
                        "40001", "Transaction retries exhausted after " + attempt + " attempts", null);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Wraps the work in a managed {@link TransactionIsolation#READ_COMMITTED}
     * transaction with {@code beginTransaction()} / {@code commit()} / {@code rollback()}.
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
        StorageContext ctx = resolveStorageContext();
        try (PersistenceConnection conn = engine.openConnection(ctx)) {
            conn.beginTransaction(isolation, readOnly);
            try {
                work.run(conn);
                conn.commit();
                lifecycleEvent.recordCommit(1);
            } catch (RuntimeException rte) {
                safeRollback(conn);
                throw rte;
            }
        }
    }

    /** {@inheritDoc} */
    @Override
    public <T> T query(Function<PersistenceConnection, T> query) {
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
        try {
            work.run(conn);
            lifecycleEvent.recordCommit(attempt);
            return null;
        } catch (PersistenceProviderException ppe) {
            safeRollback(conn);
            if (isRetryable(ppe) && attempt < retryPolicy.maxAttempts()) {
                return ppe; // caller will retry
            }
            throw ppe;
        } catch (RuntimeException unexpected) {
            safeRollback(conn);
            throw PersistenceProviderException.queryFailed(
                    "XX000",
                    "Unexpected error in transactional work: " + unexpected.getMessage(),
                    unexpected);
        }
    }

    private static StorageContext resolveStorageContext() {
        if (KernelProviders.STORAGE_CONTEXT.isBound()) {
            return KernelProviders.STORAGE_CONTEXT.get();
        }
        return ImmutableStorageContext.system();
    }

    private static void safeRollback(PersistenceConnection conn) {
        try {
            if (conn.inTransaction()) {
                conn.rollback();
            }
        } catch (PersistenceProviderException _) {
            // Best-effort — original exception takes precedence
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
