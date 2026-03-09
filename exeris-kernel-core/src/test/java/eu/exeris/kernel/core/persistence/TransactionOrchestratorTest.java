/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.core.persistence;

import eu.exeris.kernel.spi.exceptions.persistence.PersistenceProviderException;
import eu.exeris.kernel.spi.persistence.ConnectionInterceptor;
import eu.exeris.kernel.spi.persistence.EngineStats;
import eu.exeris.kernel.spi.persistence.PersistenceConnection;
import eu.exeris.kernel.spi.persistence.PersistenceEngine;
import eu.exeris.kernel.spi.persistence.PersistenceEngineCapabilities;
import eu.exeris.kernel.spi.persistence.PersistenceHealthStatus;
import eu.exeris.kernel.spi.persistence.PersistenceStatement;
import eu.exeris.kernel.spi.persistence.QueryResult;
import eu.exeris.kernel.spi.persistence.RowCursor;
import eu.exeris.kernel.spi.persistence.TransactionIsolation;
import eu.exeris.kernel.spi.security.StorageContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * L1 Unit Tests: {@link TransactionOrchestrator} — lifecycle, retry, and rollback.
 *
 * <h2>Design</h2>
 * <p>Uses hand-crafted in-memory stubs. Retry policy is configured via the
 * top-level {@link TransactionRetryPolicy} record.
 *
 * @since 0.5.0
 */
@DisplayName("L1: TransactionOrchestrator — lifecycle, retry, rollback")
class TransactionOrchestratorTest {

    // =========================================================================
    // SPI stubs (non-final for anonymous subclassing)
    // =========================================================================

    /** Singleton empty {@link QueryResult} — zero rows, no allocation. */
    private static final QueryResult EMPTY_RESULT = new QueryResult() {
        @Override public boolean next()       { return false; }
        @Override public RowCursor row()      { return null; }
        @Override public long rowsAffected()  { return -1L; }
        @Override public int columnCount()    { return 0; }
        @Override public String commandTag()  { return null; }
        @Override public void close()         { /* empty — intentional no-op for test stub */ }
    };

    /** Base {@link PersistenceConnection} stub — scriptable via fields. */
    private static class StubConnection implements PersistenceConnection {

        boolean open           = true;
        boolean inTransaction  = false;
        boolean rollbackCalled = false;
        boolean commitCalled   = false;
        boolean beginCalled    = false;
        PersistenceProviderException commitThrow;
        PersistenceProviderException queryThrow;

        @Override public PersistenceStatement prepare(String sql) {
            throw new UnsupportedOperationException("not needed");
        }
        @Override public QueryResult executeQuery(String sql) {
            if (queryThrow != null) { throw queryThrow; }
            return EMPTY_RESULT;
        }
        @Override public long executeUpdate(String sql)            { return 0; }
        @Override public void beginTransaction() {
            beginCalled = true; inTransaction = true;
        }
        @Override public void beginTransaction(TransactionIsolation iso, boolean ro) {
            beginCalled = true; inTransaction = true;
        }
        @Override public void commit() {
            if (commitThrow != null) { throw commitThrow; }
            commitCalled = true; inTransaction = false;
        }
        @Override public void rollback() {
            rollbackCalled = true; inTransaction = false;
        }
        @Override public boolean inTransaction() { return inTransaction; }
        @Override public boolean isOpen()        { return open; }
        @Override public void close()            { open = false; }
    }

    /** Base {@link PersistenceEngine} stub — override {@link #supplyConnection()} to inject behaviour. */
    private static class StubEngine implements PersistenceEngine {

        final AtomicInteger openCount = new AtomicInteger(0);
        StubConnection lastConnection;

        StubConnection supplyConnection() {
            return new StubConnection();
        }

        @Override public PersistenceConnection openConnection() {
            openCount.incrementAndGet();
            lastConnection = supplyConnection();
            return lastConnection;
        }
        @Override public PersistenceConnection openConnection(StorageContext ctx) { return openConnection(); }
        @Override public void registerInterceptor(ConnectionInterceptor i) { /* empty — test stub */ }
        @Override public PersistenceHealthStatus healthCheckDetailed()      { return PersistenceHealthStatus.ok(0L); }
        @Override public EngineStats stats()                                { return null; }
        @Override public PersistenceEngineCapabilities capabilities()       { return null; }
        @Override public void close()                                       { /* empty — test stub */ }
    }

    /**
     * Engine where first {@code failN} connections throw 40001 on commit.
     * After {@code failN} calls, connections succeed.
     */
    private static class FailingEngine extends StubEngine {

        private final int failN;
        private int callIndex = 0;

        FailingEngine(int failN) { this.failN = failN; }

        @Override
        StubConnection supplyConnection() {
            StubConnection conn = new StubConnection();
            callIndex++;
            if (callIndex <= failN) {
                conn.commitThrow = PersistenceProviderException.queryFailed(
                        "40001", "serialization failure", null);
            }
            return conn;
        }
    }

    // =========================================================================
    // Fixtures
    // =========================================================================

    private StubEngine engine;

    @BeforeEach
    void setUp() {
        engine = new StubEngine();
    }

    // =========================================================================
    // execute() — happy path
    // =========================================================================

    @Nested
    @DisplayName("execute() — happy path")
    class ExecuteHappyPath {

        @Test
        @DisplayName("execute() calls work lambda exactly once on success")
        void callsWorkOnce() {
            AtomicInteger calls = new AtomicInteger(0);
            new TransactionOrchestrator(engine).execute(ignored -> calls.incrementAndGet());
            assertThat(calls.get()).isEqualTo(1);
        }

        @Test
        @DisplayName("execute() closes the connection after work completes (try-with-resources)")
        void connectionClosedAfterWork() {
            new TransactionOrchestrator(engine).execute(ignored -> { /* empty — tests connection close */ });
            assertThat(engine.lastConnection.isOpen()).isFalse();
        }
    }

    // =========================================================================
    // execute() — non-retryable error
    // =========================================================================

    @Nested
    @DisplayName("execute() — non-retryable error (23505)")
    class ExecuteNonRetryable {

        @Test
        @DisplayName("23505 is thrown after exactly 1 attempt (not retried)")
        void uniqueViolationThrowsOnce() {
            TransactionOrchestrator tx = new TransactionOrchestrator(engine,
                    TransactionRetryPolicy.exponential(3, 0L));

            assertThatThrownBy(() -> tx.execute(ignored -> throwUnique()))
                    .isInstanceOf(PersistenceProviderException.class);

            assertThat(engine.openCount.get()).isEqualTo(1);
        }
    }

    // =========================================================================
    // execute() — retry on 40001
    // =========================================================================

    @Nested
    @DisplayName("execute() — retry on serialization failure (40001)")
    class RetryLogic {

        @Test
        @DisplayName("Succeeds on 3rd attempt when first 2 throw 40001")
        void retriesUntilSuccess() {
            FailingEngine retryEngine = new FailingEngine(2);
            TransactionOrchestrator tx = new TransactionOrchestrator(retryEngine,
                    TransactionRetryPolicy.exponential(3, 0L));

            AtomicInteger workCalls = new AtomicInteger(0);
            tx.execute(conn -> incrementAndCommit(workCalls, conn));

            assertThat(workCalls.get()).isEqualTo(3);
            assertThat(retryEngine.openCount.get()).isEqualTo(3);
        }

        @Test
        @DisplayName("Exhausts all 3 attempts and throws when every attempt fails with 40001")
        void retriesExhaustedThrows() {
            FailingEngine allFailEngine = new FailingEngine(100);
            TransactionOrchestrator tx = new TransactionOrchestrator(allFailEngine,
                    TransactionRetryPolicy.exponential(3, 0L));

            assertThatThrownBy(() -> tx.execute(PersistenceConnection::commit))
                    .isInstanceOf(PersistenceProviderException.class);

            assertThat(allFailEngine.openCount.get()).isEqualTo(3);
        }

        private static void incrementAndCommit(AtomicInteger counter, PersistenceConnection conn) {
            counter.incrementAndGet();
            conn.commit();
        }
    }

    // =========================================================================
    // execute() — unexpected RuntimeException wrapping
    // =========================================================================

    @Nested
    @DisplayName("execute() — unexpected RuntimeException propagation")
    class UnexpectedExceptionWrapping {

        @Test
        @DisplayName("Unexpected RuntimeException in work lambda is rethrown directly (consistent with executeManaged)")
        void unexpectedRuntimeExceptionRethrown() {
            TransactionOrchestrator tx = new TransactionOrchestrator(engine);

            assertThatThrownBy(() -> tx.execute(ignored -> throwUnexpected()))
                    .isInstanceOf(IllegalStateException.class)
                    .isNotInstanceOf(PersistenceProviderException.class);
        }
    }

    // =========================================================================
    // Rollback on failure
    // =========================================================================

    @Nested
    @DisplayName("Rollback safety — safeRollback()")
    class RollbackSafety {

        @Test
        @DisplayName("rollback() called when inTransaction()=true and work throws 23505")
        void rollbackCalledWhenInTransaction() {
            StubConnection conn = new StubConnection();
            conn.inTransaction = true;
            StubEngine singleConnEngine = engineReturning(conn);
            TransactionOrchestrator tx = new TransactionOrchestrator(singleConnEngine);

            assertThatThrownBy(() -> tx.execute(ignored -> throwUnique()))
                    .isInstanceOf(PersistenceProviderException.class);

            assertThat(conn.rollbackCalled).isTrue();
        }

        @Test
        @DisplayName("rollback() NOT called when inTransaction()=false")
        void rollbackNotCalledWhenNoTransaction() {
            StubConnection conn = new StubConnection();
            StubEngine singleConnEngine = engineReturning(conn);
            TransactionOrchestrator tx = new TransactionOrchestrator(singleConnEngine);

            assertThatThrownBy(() -> tx.execute(ignored -> throwUnique()))
                    .isInstanceOf(PersistenceProviderException.class);

            assertThat(conn.rollbackCalled).isFalse();
        }

        private StubEngine engineReturning(StubConnection conn) {
            return new StubEngine() {
                @Override StubConnection supplyConnection() {
                    lastConnection = conn;
                    return conn;
                }
            };
        }
    }

    // =========================================================================
    // query() — read-only path
    // =========================================================================

    @Nested
    @DisplayName("query() — read-only path")
    class QueryPath {

        @Test
        @DisplayName("query() returns lambda result and closes connection")
        void returnsValueAndClosesConnection() {
            String result = new TransactionOrchestrator(engine).query(ignored -> "ok");
            assertThat(result).isEqualTo("ok");
            assertThat(engine.lastConnection.isOpen()).isFalse();
        }

        @Test
        @DisplayName("query() propagates PersistenceProviderException from work lambda")
        void propagatesException() {
            StubConnection conn = new StubConnection();
            conn.queryThrow = PersistenceProviderException.queryFailed("42P01", "no table", null);
            StubEngine failEngine = engineReturning(conn);
            TransactionOrchestrator tx = new TransactionOrchestrator(failEngine);

            assertThatThrownBy(() -> tx.query(c -> c.executeQuery("SELECT 1")))
                    .isInstanceOf(PersistenceProviderException.class);
        }

        private StubEngine engineReturning(StubConnection conn) {
            return new StubEngine() {
                @Override StubConnection supplyConnection() {
                    lastConnection = conn;
                    return conn;
                }
            };
        }
    }

    // =========================================================================
    // executeManaged() — auto begin/commit
    // =========================================================================

    @Nested
    @DisplayName("executeManaged() — auto begin/commit")
    class ExecuteManaged {

        @Test
        @DisplayName("executeManaged() surrounds work with beginTransaction() and commit()")
        void autoBeginAndCommit() {
            StubConnection conn = new StubConnection();
            StubEngine managedEngine = new StubEngine() {
                @Override StubConnection supplyConnection() {
                    lastConnection = conn;
                    return conn;
                }
            };
            AtomicBoolean workRan = new AtomicBoolean(false);
            new TransactionOrchestrator(managedEngine).executeManaged(ignored -> workRan.set(true));

            assertThat(conn.beginCalled).isTrue();
            assertThat(conn.commitCalled).isTrue();
            assertThat(workRan.get()).isTrue();
        }

        @Test
        @DisplayName("executeManaged(SERIALIZABLE, readOnly=true) calls beginTransaction(SERIALIZABLE, true)")
        void serializableReadOnlyDelegation() {
            AtomicBoolean serializableRo = new AtomicBoolean(false);

            StubConnection trackingConn = new StubConnection() {
                @Override
                public void beginTransaction(TransactionIsolation iso, boolean ro) {
                    if (iso == TransactionIsolation.SERIALIZABLE && ro) {
                        serializableRo.set(true);
                    }
                    super.beginTransaction(iso, ro);
                }
            };
            StubEngine isolatedEngine = new StubEngine() {
                @Override StubConnection supplyConnection() {
                    lastConnection = trackingConn;
                    return trackingConn;
                }
            };
            new TransactionOrchestrator(isolatedEngine)
                    .executeManaged(TransactionIsolation.SERIALIZABLE, true, ignored -> { /* empty */ });

            assertThat(serializableRo.get()).isTrue();
        }
    }

    // =========================================================================
    // Convenience constructor — RetryPolicy.NONE
    // =========================================================================

    @Nested
    @DisplayName("Convenience constructor — TransactionRetryPolicy.NONE (1 attempt)")
    class ConvenienceConstructor {

        @Test
        @DisplayName("Single-arg constructor: 40001 triggers only 1 attempt (NONE policy)")
        void noRetryByDefault() {
            FailingEngine failEngine = new FailingEngine(1);
            TransactionOrchestrator tx = new TransactionOrchestrator(failEngine);

            assertThatThrownBy(() -> tx.execute(PersistenceConnection::commit))
                    .isInstanceOf(PersistenceProviderException.class);

            assertThat(failEngine.openCount.get()).isEqualTo(1);
        }
    }

    // =========================================================================
    // Shared throw helpers — one invocation each, accessible across nested classes
    // =========================================================================

    private static void throwUnique() {
        throw PersistenceProviderException.queryFailed("23505", "unique", null);
    }

    private static void throwUnexpected() {
        throw new IllegalStateException("unexpected");
    }
}
