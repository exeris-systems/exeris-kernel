/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.core.persistence;

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
import eu.exeris.kernel.spi.persistence.TransactionalExecutor;
import eu.exeris.kernel.spi.security.StorageContext;
import eu.exeris.kernel.tck.contract.persistence.AbstractTransactionalExecutorTck;
import org.junit.jupiter.api.DisplayName;

import java.lang.foreign.MemorySegment;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * Core TCK binding: wires {@link TransactionOrchestrator} to
 * {@link AbstractTransactionalExecutorTck}.
 *
 * <h2>Responsibility</h2>
 * <p>This is the <b>only</b> class in the TCK chain that imports
 * {@link TransactionOrchestrator} and {@link TransactionRetryPolicy}.
 * The abstract TCK is kept clean (SPI-only). This class provides:
 * <ul>
 *   <li>An in-memory stub {@link PersistenceEngine} that tracks commits and rollbacks</li>
 *   <li>Factory methods for no-retry and retry executors</li>
 *   <li>Sentinel SQL stubs backed by an in-memory counter</li>
 * </ul>
 *
 * @since 0.5.0
 */
@DisplayName("Core TCK: TransactionOrchestrator ← AbstractTransactionalExecutorTck")
class TransactionOrchestratorTckTest extends AbstractTransactionalExecutorTck {

    // =========================================================================
    // Template method implementations
    // =========================================================================

    @Override
    protected PersistenceEngine createEngine() {
        return new InMemorySentinelEngine();
    }

    @Override
    protected TransactionalExecutor createExecutorNoRetry(PersistenceEngine engine) {
        return new TransactionOrchestrator(engine, TransactionRetryPolicy.NONE);
    }

    @Override
    protected TransactionalExecutor createExecutorWithRetry(
            PersistenceEngine engine, int maxAttempts, long baseDelayMs) {
        return new TransactionOrchestrator(
                engine,
                TransactionRetryPolicy.exponential(maxAttempts, baseDelayMs));
    }

    @Override
    protected String writeSentinelSql() {
        return InMemorySentinelEngine.SQL_INSERT;
    }

    @Override
    protected String readSentinelCountSql() {
        return InMemorySentinelEngine.SQL_COUNT;
    }

    @Override
    protected String selectOneSql() {
        return InMemorySentinelEngine.SQL_SELECT_ONE;
    }

    // =========================================================================
    // In-memory stub engine — no external DB required
    // =========================================================================

    /**
     * In-memory {@link PersistenceEngine} stub that implements the minimal contract
     * needed by the TCK. Backed by a thread-safe deque for sentinel row tracking.
     *
     * <p>SQL "execution" is handled by simple string matching on magic SQL constants.
     */
    static final class InMemorySentinelEngine implements PersistenceEngine {

        static final String SQL_INSERT     = "__TCK_INSERT_SENTINEL__";
        static final String SQL_COUNT      = "__TCK_COUNT_SENTINEL__";
        static final String SQL_SELECT_ONE = "__TCK_SELECT_ONE__";

        /** Committed sentinel rows — added on commit, cleared on rollback. */
        private final ConcurrentLinkedDeque<String> committed = new ConcurrentLinkedDeque<>();
        private volatile boolean closed = false;

        @Override
        public PersistenceConnection openConnection() {
            ensureOpen();
            return new InMemorySentinelConnection(committed);
        }

        @Override
        public PersistenceConnection openConnection(StorageContext storageContext) {
            return openConnection();
        }

        @Override
        public PersistenceHealthStatus healthCheckDetailed() {
            return !closed ? PersistenceHealthStatus.ok(0L)
                           : PersistenceHealthStatus.failed("InMemorySentinelEngine is closed");
        }


        @Override
        public PersistenceEngineCapabilities capabilities() {
            return PersistenceEngineCapabilities.DEFAULT;
        }

        @Override
        public EngineStats stats() {
            return EngineStats.empty();
        }

        @Override
        public void registerInterceptor(ConnectionInterceptor interceptor) {
            // no-op for stub
        }

        @Override
        public void close() {
            closed = true;
        }

        private void ensureOpen() {
            if (closed) {
                throw new IllegalStateException("InMemorySentinelEngine is closed");
            }
        }
    }

    // =========================================================================
    // In-memory stub connection
    // =========================================================================

    /**
     * In-memory {@link PersistenceConnection} stub.
     * Maintains a staging area — rows are "written" to staging on INSERT
     * and flushed to {@code committed} on COMMIT, discarded on ROLLBACK.
     */
    private static final class InMemorySentinelConnection implements PersistenceConnection {

        private final ConcurrentLinkedDeque<String> committed;
        private final ConcurrentLinkedDeque<String> staging = new ConcurrentLinkedDeque<>();
        private boolean inTransaction = false;
        private boolean closed = false;

        private InMemorySentinelConnection(ConcurrentLinkedDeque<String> committed) {
            this.committed = committed;
        }

        @Override
        public PersistenceStatement prepare(String sql) {
            return new NoOpStatement(sql, this);
        }

        @Override
        public QueryResult executeQuery(String sql) {
            ensureOpen();
            if (InMemorySentinelEngine.SQL_COUNT.equals(sql)) {
                int count = committed.size();
                return new SingleIntResult(count);
            }
            if (InMemorySentinelEngine.SQL_SELECT_ONE.equals(sql)) {
                return new SingleIntResult(1);
            }
            return EmptyResult.INSTANCE;
        }

        @Override
        public long executeUpdate(String sql) {
            ensureOpen();
            if (InMemorySentinelEngine.SQL_INSERT.equals(sql)) {
                staging.add("row-" + System.nanoTime());
                return 1L;
            }
            return 0L;
        }

        @Override
        public void beginTransaction() {
            beginTransaction(TransactionIsolation.READ_COMMITTED, false);
        }

        @Override
        public void beginTransaction(TransactionIsolation isolation, boolean readOnly) {
            ensureOpen();
            inTransaction = true;
            staging.clear(); // fresh staging per transaction
        }

        @Override
        public void commit() {
            ensureOpen();
            committed.addAll(staging);
            staging.clear();
            inTransaction = false;
        }

        @Override
        public void rollback() {
            staging.clear();
            inTransaction = false;
        }

        @Override
        public boolean inTransaction() {
            return inTransaction;
        }

        @Override
        public boolean isOpen() {
            return !closed;
        }

        @Override
        public void close() {
            if (!closed && inTransaction) {
                rollback(); // best-effort rollback on close
            }
            closed = true;
        }

        private void ensureOpen() {
            if (closed) {
                throw new IllegalStateException("InMemorySentinelConnection is closed");
            }
        }
    }

    // =========================================================================
    // Minimal QueryResult stubs
    // =========================================================================

    private static final class SingleIntResult implements QueryResult {
        private boolean consumed = false;
        private final SingleIntCursor cursor;

        private SingleIntResult(int value) {
            this.cursor = new SingleIntCursor(value);
        }

        @Override public boolean next()      { if (consumed) return false; consumed = true; return true; }
        @Override public RowCursor row()     { return cursor; }
        @Override public long rowsAffected() { return 1L; }
        @Override public int columnCount()   { return 1; }
        @Override public String commandTag() { return "SELECT 1"; }
        @Override public void close()        { /* no-op */ }
    }

    private static final class SingleIntCursor implements RowCursor {
        private final int value;
        private SingleIntCursor(int value) { this.value = value; }

        @Override public int     getInt(int col)            { return value; }
        @Override public long    getLong(int col)           { return value; }
        @Override public short   getShort(int col)          { return (short) value; }
        @Override public float   getFloat(int col)          { return value; }
        @Override public double  getDouble(int col)         { return value; }
        @Override public boolean getBoolean(int col)        { return value != 0; }
        @Override public boolean isNull(int col)            { return false; }
        @Override public boolean isValid()                  { return true; }
        @Override public MemorySegment getSegment(int col)  { return null; }
        @Override public int     getLength(int col)         { return Integer.BYTES; }
        @Override public String  getString(int col)         { return String.valueOf(value); }
        @Override public byte[]  getBytes(int col)          { return null; }
        @Override public UUID    getUuid(int col)           { return null; }
        @Override public int     columnCount()              { return 1; }
    }

    private static final class EmptyResult implements QueryResult {
        static final EmptyResult INSTANCE = new EmptyResult();
        @Override public boolean next()      { return false; }
        @Override public RowCursor row()     { return null; }
        @Override public long rowsAffected() { return 0L; }
        @Override public int columnCount()   { return 0; }
        @Override public String commandTag() { return null; }
        @Override public void close()        { /* no-op */ }
    }

    private static final class NoOpStatement implements PersistenceStatement {
        private final String sql;
        private final InMemorySentinelConnection conn;

        private NoOpStatement(String sql, InMemorySentinelConnection conn) {
            this.sql  = sql;
            this.conn = conn;
        }

        @Override public PersistenceStatement bindInt(int idx, int v)        { return this; }
        @Override public PersistenceStatement bindLong(int idx, long v)      { return this; }
        @Override public PersistenceStatement bindShort(int idx, short v)    { return this; }
        @Override public PersistenceStatement bindFloat(int idx, float v)    { return this; }
        @Override public PersistenceStatement bindDouble(int idx, double v)  { return this; }
        @Override public PersistenceStatement bindBoolean(int idx, boolean v){ return this; }
        @Override public PersistenceStatement bindString(int idx, String v)  { return this; }
        @Override public PersistenceStatement bindBytes(int idx, byte[] v)   { return this; }
        @Override public PersistenceStatement bindNull(int idx)              { return this; }
        @Override public QueryResult executeQuery()  { return conn.executeQuery(sql); }
        @Override public long executeUpdate()        { return conn.executeUpdate(sql); }
        @Override public void close()                { /* no-op */ }
    }
}

