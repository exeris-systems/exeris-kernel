/*
 * Copyright (C) 2025-2026 Exeris. All rights reserved.
 *
 * This code is part of the Exeris Systems.
 * Distributed under the proprietary Exeris Software License.
 * Unauthorized copying or distribution is prohibited.
 */
package eu.exeris.kernel.tck.contract.persistence;

import eu.exeris.kernel.spi.exceptions.persistence.PersistenceProviderException;
import eu.exeris.kernel.spi.persistence.PersistenceEngine;
import eu.exeris.kernel.spi.persistence.QueryResult;
import eu.exeris.kernel.spi.persistence.TransactionalExecutor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * TCK: Abstract base for {@link TransactionalExecutor} contract verification.
 *
 * <h2>The Wall (SPI Compliance)</h2>
 * <p>This TCK depends <b>only</b> on {@code exeris-kernel-spi}. It has zero knowledge
 * of {@code TransactionOrchestrator}, {@code RetryPolicy}, or any Core/Community/Enterprise
 * class. Subclasses in {@code exeris-kernel-core} wire the concrete implementation.
 *
 * <h2>Contract</h2>
 * <ul>
 *   <li>Managed transactions auto-commit on success</li>
 *   <li>Managed transactions auto-rollback on exception</li>
 *   <li>Read-only queries execute without transaction boundaries</li>
 *   <li>Executor with retry retries on {@code 40001} (serialization failure)</li>
 *   <li>Executor without retry fails immediately on {@code 40001}</li>
 *   <li>No {@code ThreadLocal} — concurrent VT execution is isolation-safe</li>
 * </ul>
 *
 * @since 0.5.0
 */
@DisplayName("TCK: TransactionalExecutor contract")
public abstract class AbstractTransactionOrchestratorTck {

    // =========================================================================
    // Template methods — subclasses provide the executor and engine
    // =========================================================================

    /**
     * Creates a fully bootstrapped {@link PersistenceEngine}.
     * Must be backed by a real or in-memory database.
     */
    protected abstract PersistenceEngine createEngine();

    /**
     * Creates a {@link TransactionalExecutor} with <b>no retry</b> (single attempt).
     *
     * <p>Concrete implementations use {@code new TransactionOrchestrator(engine, RetryPolicy.NONE)}
     * or equivalent. The TCK does not need to know the retry mechanism — only its behaviour.
     *
     * @param engine the engine to bind the executor to
     * @return executor with no retry
     */
    protected abstract TransactionalExecutor createExecutorNoRetry(PersistenceEngine engine);

    /**
     * Creates a {@link TransactionalExecutor} with exponential retry.
     *
     * @param engine      the engine to bind the executor to
     * @param maxAttempts maximum number of total attempts
     * @param baseDelayMs base delay between retries in milliseconds
     * @return executor with exponential retry
     */
    protected abstract TransactionalExecutor createExecutorWithRetry(
            PersistenceEngine engine, int maxAttempts, long baseDelayMs);

    /**
     * Returns a SQL statement that writes a sentinel value atomically.
     * Example: {@code "INSERT INTO sentinel_tck(val) VALUES ('ok')"}
     */
    protected abstract String writeSentinelSql();

    /**
     * Returns a SQL statement that reads sentinel row count (int at column 0).
     * Example: {@code "SELECT COUNT(*) FROM sentinel_tck"}
     */
    protected abstract String readSentinelCountSql();

    /**
     * Returns a SQL query that returns at least one row with an int at column 0.
     * Example: {@code "SELECT 1"}
     */
    protected abstract String selectOneSql();

    // =========================================================================
    // Fixtures
    // =========================================================================

    private PersistenceEngine   engine;
    private TransactionalExecutor executor;

    @BeforeEach
    final void setUp() {
        engine   = createEngine();
        executor = createExecutorNoRetry(engine);
    }

    @AfterEach
    final void tearDown() {
        engine.close();
    }

    // =========================================================================
    // Query contract (read-only)
    // =========================================================================

    @Nested
    @DisplayName("Query (read-only) contract")
    class QueryContract {

        @Test
        @DisplayName("query() returns a non-null result")
        @Timeout(value = 5, unit = TimeUnit.SECONDS)
        void queryReturnsResult() {
            Integer result = executor.query(conn -> {
                try (QueryResult qr = conn.executeQuery(selectOneSql())) {
                    org.assertj.core.api.Assertions.assertThat(qr.next()).isTrue();
                    return qr.row().getInt(0);
                }
            });
            assertThat(result).isNotNull();
        }

        @Test
        @DisplayName("query() releases connection after each call (no exhaustion on repeat)")
        @Timeout(value = 5, unit = TimeUnit.SECONDS)
        void queryReleasesConnection() {
            for (int i = 0; i < 5; i++) {
                executor.query(conn -> {
                    try (QueryResult qr = conn.executeQuery(selectOneSql())) {
                        org.assertj.core.api.Assertions.assertThat(qr.next()).isTrue();
                        return qr.row().getInt(0);
                    }
                });
            }
             Integer result = executor.query(conn -> {
                try (QueryResult qr = conn.executeQuery(selectOneSql())) {
                    org.assertj.core.api.Assertions.assertThat(qr.next()).isTrue();
                    return qr.row().getInt(0);
                }
            });
            assertThat(result)
                    .as("connection must still be obtainable after 5 successive queries — pool not exhausted")
                    .isNotNull();
        }
    }

    // =========================================================================
    // Managed transaction contract (write path)
    // =========================================================================

    @Nested
    @DisplayName("Managed transaction contract")
    class ManagedTransactionContract {

        @Test
        @DisplayName("executeManaged() commits on success")
        @Timeout(value = 5, unit = TimeUnit.SECONDS)
        void commitOnSuccess() {
            executor.executeManaged(conn -> conn.executeUpdate(writeSentinelSql()));

            Integer count = executor.query(conn -> {
                try (QueryResult qr = conn.executeQuery(readSentinelCountSql())) {
                    org.assertj.core.api.Assertions.assertThat(qr.next()).isTrue();
                    return qr.row().getInt(0);
                }
            });
            assertThat(count).isGreaterThanOrEqualTo(1);
        }

        @Test
        @DisplayName("executeManaged() rolls back on exception — count unchanged")
        @Timeout(value = 5, unit = TimeUnit.SECONDS)
        void rollbackOnException() {
            int countBefore = executor.query(conn -> {
                try (QueryResult qr = conn.executeQuery(readSentinelCountSql())) {
                    org.assertj.core.api.Assertions.assertThat(qr.next()).isTrue();
                    return qr.row().getInt(0);
                }
            });

            assertThatThrownBy(() ->
                    executor.executeManaged(conn -> {
                        conn.executeUpdate(writeSentinelSql());
                        throw new TckForcedRollbackException();
                    })
            ).isInstanceOf(TckForcedRollbackException.class);

            int countAfter = executor.query(conn -> {
                try (QueryResult qr = conn.executeQuery(readSentinelCountSql())) {
                    org.assertj.core.api.Assertions.assertThat(qr.next()).isTrue();
                    return qr.row().getInt(0);
                }
            });

            assertThat(countAfter)
                    .as("count must not increase after rollback")
                    .isEqualTo(countBefore);
        }

        @Test
        @DisplayName("executeManaged() invokes the work lambda exactly once (no phantom retry)")
        @Timeout(value = 5, unit = TimeUnit.SECONDS)
        void workLambdaInvokedOnce() {
            AtomicInteger invocations = new AtomicInteger(0);
            executor.executeManaged(conn -> {
                invocations.incrementAndGet();
                conn.executeUpdate(writeSentinelSql());
            });
            assertThat(invocations.get()).isOne();
        }
    }

    // =========================================================================
    // Retry contract (behaviour-only — no RetryPolicy type import)
    // =========================================================================

    @Nested
    @DisplayName("Retry behaviour contract")
    class RetryBehaviourContract {

        @Test
        @DisplayName("no-retry executor: fails after exactly 1 attempt on 40001")
        @Timeout(value = 5, unit = TimeUnit.SECONDS)
        void noRetryFailsAfterOneAttempt() {
            AtomicInteger attempts = new AtomicInteger(0);

            assertThatThrownBy(() ->
                    executor.execute(conn -> {
                        conn.beginTransaction();
                        attempts.incrementAndGet();
                        throw PersistenceProviderException.queryFailed(
                                "40001", "forced serialization failure", null);
                    })
            ).isInstanceOf(PersistenceProviderException.class);

            assertThat(attempts.get()).as("no-retry executor must attempt exactly once").isOne();
        }

        @Test
        @DisplayName("retry executor: retries up to maxAttempts on 40001")
        @Timeout(value = 10, unit = TimeUnit.SECONDS)
        void retryExecutorRetriesUpToMax() {
            int maxAttempts = 3;
            AtomicInteger attempts = new AtomicInteger(0);
            TransactionalExecutor retryExecutor =
                    createExecutorWithRetry(engine, maxAttempts, 10L);

            assertThatThrownBy(() ->
                    retryExecutor.execute(conn -> {
                        conn.beginTransaction();
                        attempts.incrementAndGet();
                        throw PersistenceProviderException.queryFailed(
                                "40001", "forced serialization failure", null);
                    })
            ).isInstanceOf(PersistenceProviderException.class);

            assertThat(attempts.get())
                    .as("retry executor must attempt exactly maxAttempts times")
                    .isEqualTo(maxAttempts);
        }

        @Test
        @DisplayName("retry executor: does NOT retry on non-retryable SQLSTATE 42601")
        @Timeout(value = 5, unit = TimeUnit.SECONDS)
        void retryExecutorDoesNotRetryOnSyntaxError() {
            AtomicInteger attempts = new AtomicInteger(0);
            TransactionalExecutor retryExecutor =
                    createExecutorWithRetry(engine, 3, 10L);

            assertThatThrownBy(() ->
                    retryExecutor.execute(conn -> {
                        conn.beginTransaction();
                        attempts.incrementAndGet();
                        throw PersistenceProviderException.queryFailed(
                                "42601", "syntax error near 'BORK'", null);
                    })
            ).isInstanceOf(PersistenceProviderException.class);

            assertThat(attempts.get())
                    .as("non-retryable SQLSTATE 42601 must not be retried")
                    .isOne();
        }

        @Test
        @DisplayName("retry executor: succeeds on second attempt after transient failure")
        @Timeout(value = 5, unit = TimeUnit.SECONDS)
        void retryExecutorSucceedsOnSecondAttempt() {
            AtomicInteger attempts = new AtomicInteger(0);
            TransactionalExecutor retryExecutor =
                    createExecutorWithRetry(engine, 3, 10L);

            // First attempt throws 40001, second succeeds
            retryExecutor.execute(conn -> {
                conn.beginTransaction();
                if (attempts.incrementAndGet() == 1) {
                    throw PersistenceProviderException.queryFailed(
                            "40001", "transient serialization failure", null);
                }
                // Second attempt: commit and succeed
                conn.commit();
            });

            assertThat(attempts.get())
                    .as("executor should succeed on second attempt")
                    .isEqualTo(2);
        }
    }

    // =========================================================================
    // VT isolation contract (no ThreadLocal bleed)
    // =========================================================================

    @Nested
    @DisplayName("VT isolation contract — no ThreadLocal bleed")
    class VtIsolationContract {

        @Test
        @DisplayName("concurrent queries complete without connection sharing")
        @Timeout(value = 10, unit = TimeUnit.SECONDS)
        void concurrentQueriesDoNotShareConnection() throws Exception {
            AtomicInteger completed = new AtomicInteger(0);
            try (var scope = java.util.concurrent.StructuredTaskScope.open(
                    java.util.concurrent.StructuredTaskScope.Joiner.awaitAllSuccessfulOrThrow())) {
                for (int i = 0; i < 10; i++) {
                    scope.fork(() -> {
                        executor.query(conn -> {
                            assertThat(conn.isOpen()).isTrue();
                            completed.incrementAndGet();
                            return null;
                        });
                        return null;
                    });
                }
                scope.join();
            }
            assertThat(completed.get()).isEqualTo(10);
        }
    }

    // =========================================================================
    // Inner: test-only exception for rollback simulation
    // =========================================================================

    /**
     * Test-only unchecked exception to force rollback in TCK tests.
     * Must NOT appear in production code.
     */
    public static final class TckForcedRollbackException extends RuntimeException {
        public TckForcedRollbackException() {
            super("TCK: forced rollback for rollback-contract verification");
        }
    }
}

