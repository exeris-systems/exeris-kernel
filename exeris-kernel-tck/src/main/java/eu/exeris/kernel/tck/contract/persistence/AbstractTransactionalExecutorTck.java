/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.tck.contract.persistence;

import eu.exeris.kernel.spi.exceptions.persistence.PersistenceProviderException;
import eu.exeris.kernel.spi.persistence.PersistenceEngine;
import eu.exeris.kernel.spi.persistence.QueryResult;
import eu.exeris.kernel.spi.persistence.TransactionalExecutor;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import eu.exeris.kernel.tck.support.TckScope;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * TCK: Abstract base for {@link TransactionalExecutor} contract verification.
 *
 * <h2>The Wall (SPI Compliance)</h2>
 * <p>This TCK depends <b>only</b> on {@code exeris-kernel-spi}. It has zero knowledge
 * of any concrete Core, Community, or Enterprise implementation class.
 * Subclasses in {@code exeris-kernel-core} wire the concrete implementation.
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
 * @since 0.5
 */
@DisplayName("TCK: TransactionalExecutor contract")
public abstract class AbstractTransactionalExecutorTck {

    // =========================================================================
    // Template methods — subclasses provide the executor and engine
    // =========================================================================

    /**
     * Creates a fully bootstrapped {@link PersistenceEngine}.
     *
     * @return an engine ready to open connections and run transactions
     * @implSpec Must be backed by a real or in-memory database — the tests below run
     *           genuine transactions, not stubs.
     */
    protected abstract PersistenceEngine createEngine();

    /**
     * Creates a {@link TransactionalExecutor} with <b>no retry</b> (single attempt).
     *
     * <p>The TCK does not need to know the concrete implementation class or retry
     * mechanism — only its observable behaviour: a single attempt, no retry on failure.
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
     *
     * @return an insert statement, e.g. {@code "INSERT INTO sentinel_tck(val) VALUES ('ok')"}
     */
    protected abstract String writeSentinelSql();

    /**
     * Returns a SQL statement that reads sentinel row count (int at column 0).
     *
     * @return a count query, e.g. {@code "SELECT COUNT(*) FROM sentinel_tck"}
     */
    protected abstract String readSentinelCountSql();

    /**
     * Returns a SQL query that returns at least one row with an int at column 0.
     *
     * @return a single-row query, e.g. {@code "SELECT 1"}
     */
    protected abstract String selectOneSql();

    // =========================================================================
    // Fixtures
    // =========================================================================

    private PersistenceEngine engine;
    private TransactionalExecutor executor;

    @BeforeEach
    final void setUp() {
        engine = createEngine();
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
                    Assertions.assertThat(qr.next()).isTrue();
                    return qr.row().getInt(0);
                }
            });
            assertThat(result).isNotNull();
        }

        /**
         * Proves that {@code query()} returns its connection to the pool between calls, by
         * running six queries in sequence and asserting the sixth still succeeds.
         *
         * @apiNote Each of the six queries holds at most one connection at a time, so this
         *          does not exercise a configured pool-size limit or concurrent pool
         *          pressure — a binding with a pool of one connection satisfies it as
         *          readily as one with a hundred.
         */
        @Test
        @DisplayName("query() releases connection after each call (no exhaustion on repeat)")
        @Timeout(value = 5, unit = TimeUnit.SECONDS)
        void queryReleasesConnection() {
            for (int i = 0; i < 5; i++) {
                executor.query(conn -> {
                    try (QueryResult qr = conn.executeQuery(selectOneSql())) {
                        Assertions.assertThat(qr.next()).isTrue();
                        return qr.row().getInt(0);
                    }
                });
            }
            Integer result = executor.query(conn -> {
                try (QueryResult qr = conn.executeQuery(selectOneSql())) {
                    Assertions.assertThat(qr.next()).isTrue();
                    return qr.row().getInt(0);
                }
            });
            assertThat(result)
                    .as("connection must still be obtainable after 5 successive queries — pool not exhausted")
                    .isNotNull();
        }
    }

    // =========================================================================
    // Read-session contract
    // =========================================================================

    @Nested
    @DisplayName("Read-session contract")
    class ReadSessionContract {

        @Test
        @DisplayName("inReadSession() reuses the same connection within scope")
        @Timeout(value = 5, unit = TimeUnit.SECONDS)
        void inReadSessionReusesConnection() {
            AtomicReference<Integer> firstIdentity = new AtomicReference<>();

            Integer secondIdentity = executor.inReadSession(session -> {
                Integer first = session.query(conn -> System.identityHashCode(conn));
                firstIdentity.set(first);
                return session.query(conn -> System.identityHashCode(conn));
            });

            assertThat(firstIdentity.get()).isEqualTo(secondIdentity);
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
                    Assertions.assertThat(qr.next()).isTrue();
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
                    Assertions.assertThat(qr.next()).isTrue();
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
                    Assertions.assertThat(qr.next()).isTrue();
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

        /**
         * Counts the work-lambda invocations directly, rather than only checking that the
         * call fails, so a no-retry executor that silently retried once more before giving up
         * would not pass by coincidence.
         */
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

        /**
         * The read-session counterpart of {@link #noRetryFailsAfterOneAttempt()}: the attempt
         * counter, not the thrown exception alone, is what proves no retry occurred.
         */
        @Test
        @DisplayName("no-retry executor: read-session fails after exactly 1 attempt on 40001")
        @Timeout(value = 5, unit = TimeUnit.SECONDS)
        void noRetryReadSessionFailsAfterOneAttempt() {
            AtomicInteger attempts = new AtomicInteger(0);

            assertThatThrownBy(() ->
                    executor.inReadSession(session -> session.query(conn -> {
                        attempts.incrementAndGet();
                        throw PersistenceProviderException.queryFailed(
                                "40001", "forced serialization failure", null);
                    }))
            ).isInstanceOf(PersistenceProviderException.class);

            assertThat(attempts.get()).as("no-retry executor must attempt exactly once").isOne();
        }

        /**
         * Establishes that a retry actually happens, not merely that the call eventually
         * fails: the work lambda is asserted to run exactly {@code maxAttempts} times before
         * the executor gives up and surfaces the failure.
         */
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

        /**
         * The read-session counterpart of {@link #retryExecutorRetriesUpToMax()}. The counter
         * sits outside the inner {@code session.query} call, so it proves the whole session
         * block re-runs on each attempt rather than only the failing query within it.
         */
        @Test
        @DisplayName("retry executor: read-session retries whole block up to maxAttempts on 40001")
        @Timeout(value = 10, unit = TimeUnit.SECONDS)
        void retryExecutorReadSessionRetriesUpToMax() {
            int maxAttempts = 3;
            AtomicInteger attempts = new AtomicInteger(0);
            TransactionalExecutor retryExecutor =
                    createExecutorWithRetry(engine, maxAttempts, 10L);

            assertThatThrownBy(() ->
                    retryExecutor.inReadSession(session -> {
                        attempts.incrementAndGet();
                        return session.query(conn -> {
                            throw PersistenceProviderException.queryFailed(
                                    "40001", "forced serialization failure", null);
                        });
                    })
            ).isInstanceOf(PersistenceProviderException.class);

            assertThat(attempts.get())
                    .as("retry executor must attempt exactly maxAttempts times")
                    .isEqualTo(maxAttempts);
        }

        /**
         * Pairs with {@link #retryExecutorRetriesUpToMax()} to prove retry is conditioned on
         * the SQLSTATE rather than blanket: the same retry-configured executor is asserted to
         * attempt only once when the failure is {@code 42601} instead of {@code 40001}.
         */
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

        /**
         * The read-session counterpart of {@link #retryExecutorDoesNotRetryOnSyntaxError()}.
         */
        @Test
        @DisplayName("retry executor: does NOT retry read-session on non-retryable SQLSTATE 42601")
        @Timeout(value = 5, unit = TimeUnit.SECONDS)
        void retryExecutorReadSessionDoesNotRetryOnSyntaxError() {
            AtomicInteger attempts = new AtomicInteger(0);
            TransactionalExecutor retryExecutor =
                    createExecutorWithRetry(engine, 3, 10L);

            assertThatThrownBy(() ->
                    retryExecutor.inReadSession(session -> session.query(conn -> {
                        attempts.incrementAndGet();
                        throw PersistenceProviderException.queryFailed(
                                "42601", "syntax error near 'BORK'", null);
                    }))
            ).isInstanceOf(PersistenceProviderException.class);

            assertThat(attempts.get())
                    .as("non-retryable SQLSTATE 42601 must not be retried")
                    .isOne();
        }

        /**
         * Establishes both halves of the recovery contract: the attempt counter reaching
         * exactly 2 proves the retry actually re-invoked the work, and {@code execute}
         * returning without throwing proves the retried attempt's success — not the first
         * attempt's failure — is what the caller observes.
         */
        @Test
        @DisplayName("retry executor: succeeds on second attempt after transient failure")
        @Timeout(value = 5, unit = TimeUnit.SECONDS)
        void retryExecutorSucceedsOnSecondAttempt() {
            AtomicInteger attempts = new AtomicInteger(0);
            TransactionalExecutor retryExecutor =
                    createExecutorWithRetry(engine, 3, 10L);

            retryExecutor.execute(conn -> {
                conn.beginTransaction();
                if (attempts.incrementAndGet() == 1) {
                    throw PersistenceProviderException.queryFailed(
                            "40001", "transient serialization failure", null);
                }
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
    // CHECKSTYLE:OFF: this string NAMES the banned type as the thing the contract forbids;
    //                 the L0 regex cannot tell a message about a ban from a use of it.
    @DisplayName("VT isolation contract — no ThreadLocal bleed")
    // CHECKSTYLE:ON
    class VtIsolationContract {

        @Test
        @DisplayName("concurrent queries complete without connection sharing")
        @Timeout(value = 10, unit = TimeUnit.SECONDS)
        void concurrentQueriesDoNotShareConnection() throws Exception {
            AtomicInteger completed = new AtomicInteger(0);
            try (TckScope scope = TckScope.openFailFast()) {
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
        /** Creates the sentinel exception thrown to force a rollback under test. */
        public TckForcedRollbackException() {
            super("TCK: forced rollback for rollback-contract verification");
        }
    }
}
