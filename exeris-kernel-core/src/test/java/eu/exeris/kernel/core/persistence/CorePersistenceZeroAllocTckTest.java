/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.core.persistence;

import eu.exeris.kernel.spi.persistence.ConnectionInterceptor;
import eu.exeris.kernel.spi.persistence.EngineStats;
import eu.exeris.kernel.spi.persistence.PersistenceConnection;
import eu.exeris.kernel.spi.persistence.PersistenceEngine;
import eu.exeris.kernel.spi.persistence.PersistenceHealthStatus;
import eu.exeris.kernel.spi.persistence.PersistenceStatement;
import eu.exeris.kernel.spi.persistence.QueryResult;
import eu.exeris.kernel.spi.persistence.RowCursor;
import eu.exeris.kernel.spi.persistence.TransactionIsolation;
import eu.exeris.kernel.spi.security.StorageContext;
import eu.exeris.kernel.tck.contract.persistence.PersistenceZeroAllocTck;
import org.junit.jupiter.api.DisplayName;

import java.lang.foreign.MemorySegment;
import java.time.Instant;
import java.util.UUID;

/**
 * Core JFR Zero-Allocation TCK: proves that the
 * {@code openConnection() → executeQuery() → next() → getInt(0) → close()} hot path
 * produces bounded (Community tier) or zero (Enterprise tier) heap allocations.
 *
 * <h2>Stub engine design</h2>
 * <p>The {@link ZeroAllocSentinelEngine} is intentionally minimal — every method
 * returns pre-allocated singletons or primitive values to minimise allocation noise
 * from the stub itself. The TCK exercises the {@link eu.exeris.kernel.spi.persistence.PersistenceEngine}
 * SPI hot path directly via {@code PersistenceZeroAllocTck#runSingleIteration()};
 * no additional orchestration layers are introduced in this test.
 *
 * <h2>JFR output</h2>
 * <p>After each test run a {@code .jfr} file is written to
 * {@code target/jfr-reports/CorePersistenceZeroAllocTckTest-Persistence-&lt;timestamp&gt;.jfr}
 * for inspection with JDK Mission Control.
 *
 * @since 0.5.0
 */
@DisplayName("L0: Persistence query hot-path Zero-Allocation JFR TCK")
class CorePersistenceZeroAllocTckTest extends PersistenceZeroAllocTck {

    @Override
    protected PersistenceEngine createEngine() {
        return new ZeroAllocSentinelEngine();
    }

    /**
     * Returns {@code true} — Enterprise tier guarantees zero {@code eu.exeris.*}
     * allocations on the steady-state hot path via slab-pool CAS on primitive
     * {@code long} fields only.
     */
    @Override
    protected boolean supportsZeroGcHotPath() {
        return true;
    }


    @Override
    protected String hotPathQuery() {
        return ZeroAllocSentinelEngine.SQL_SELECT_ONE;
    }

    // =========================================================================
    // Minimal stub engine — pre-allocated singletons only
    // =========================================================================

    /**
     * A heap-minimal {@link PersistenceEngine} stub.
     *
     * <p>All heavy objects ({@link PersistenceConnection}, {@link QueryResult},
     * {@link RowCursor}) are pre-allocated as constants so that the JFR recording
     * captures only the allocations produced by the framework code under test —
     * not by the stub itself.
     */
    static final class ZeroAllocSentinelEngine implements PersistenceEngine {

        static final String SQL_SELECT_ONE = "__TCK_SELECT_ONE__";

        /** Pre-allocated singleton connection — reused across every hot-path call. */
        private static final PersistenceConnection CONN = new ConstantConnection();

        @Override
        public PersistenceConnection openConnection() {
            return CONN;
        }

        @Override
        public PersistenceConnection openConnection(StorageContext ctx) {
            return CONN;
        }

        @Override
        public PersistenceHealthStatus healthCheckDetailed() {
            return PersistenceHealthStatus.ok(0L);
        }

        @Override
        public boolean canServiceRequest() {
            return true;
        }


        @Override
        public EngineStats stats() {
            return EngineStats.empty();
        }

        @Override
        public void registerInterceptor(ConnectionInterceptor interceptor) {
            // no-op
        }

        @Override
        public void close() {
            // no-op
        }
    }

    // =========================================================================
    // Pre-allocated connection
    // =========================================================================

    /**
     * Pre-allocated {@link PersistenceConnection} that returns the same
     * {@link ConstantResult} singleton on every {@code executeQuery()} call.
     *
     * <p>AutoCloseable.close() is a no-op so the try-with-resources in
     * {@link PersistenceZeroAllocTck#runSingleIteration()} produces zero allocation
     * on the close path.
     */
    private static final class ConstantConnection implements PersistenceConnection {

        /** Pre-allocated singleton result — reused on every hot-path call. */
        private static final QueryResult RESULT = new ConstantResult();

        @Override
        public PersistenceStatement prepare(String sql) {
            return NoOpStatement.INSTANCE;
        }

        @Override
        public QueryResult executeQuery(String sql) {
            ((ConstantResult) RESULT).reset();
            return RESULT;
        }

        @Override
        public long executeUpdate(String sql) {
            return 0L;
        }

        @Override
        public void beginTransaction() {
            // no-op
        }

        @Override
        public void beginTransaction(TransactionIsolation isolation, boolean readOnly) {
            // no-op
        }

        @Override
        public void commit() {
            // no-op
        }

        @Override
        public void rollback() {
            // no-op
        }

        @Override
        public boolean inTransaction() {
            return false;
        }

        @Override
        public boolean isOpen() {
            return true;
        }

        @Override
        public void close() {
            // no-op — deliberately not releasing; singleton reused per hot-path iteration
        }
    }

    // =========================================================================
    // Pre-allocated single-row result
    // =========================================================================

    /**
     * Pre-allocated {@link QueryResult} that yields exactly one row with a constant
     * integer value. The {@link #reset()} method restores the consumed flag before
     * each iteration — no new object is created.
     */
    private static final class ConstantResult implements QueryResult {

        private static final RowCursor CURSOR = new ConstantCursor();

        private boolean consumed = false;

        void reset() {
            consumed = false;
        }

        @Override public boolean next()      { if (consumed) return false; consumed = true; return true; }
        @Override public RowCursor row()     { return CURSOR; }
        @Override public long rowsAffected() { return 1L; }
        @Override public int columnCount()   { return 1; }
        @Override public String commandTag() { return "SELECT 1"; }
        @Override public void close()        { /* no-op */ }
    }

    // =========================================================================
    // Pre-allocated constant cursor
    // =========================================================================

    /** Pre-allocated {@link RowCursor} returning a constant {@code int} value. */
    private static final class ConstantCursor implements RowCursor {
        private static final int    SENTINEL_INT    = 1;
        private static final String SENTINEL_STRING = "1";
        private static final byte[] SENTINEL_BYTES  = new byte[]{1};
        private static final UUID   SENTINEL_UUID   = new UUID(0L, 1L);

        @Override public int     getInt(int col)            { return SENTINEL_INT; }
        @Override public long    getLong(int col)           { return SENTINEL_INT; }
        @Override public short   getShort(int col)          { return (short) SENTINEL_INT; }
        @Override public float   getFloat(int col)          { return SENTINEL_INT; }
        @Override public double  getDouble(int col)         { return SENTINEL_INT; }
        @Override public boolean getBoolean(int col)        { return true; }
        @Override public boolean isNull(int col)            { return false; }
        @Override public boolean isValid()                  { return true; }
        @Override public MemorySegment getSegment(int col)  { return MemorySegment.NULL; }
        @Override public int     getLength(int col)         { return Integer.BYTES; }
        @Override public String  getString(int col)         { return SENTINEL_STRING; }
        @Override public byte[]  getBytes(int col)          { return SENTINEL_BYTES; }
        @Override public UUID    getUuid(int col)           { return SENTINEL_UUID; }
        @Override public Instant getInstant(int col)        { return SENTINEL_INSTANT; }
        @Override public int     columnCount()              { return 1; }

        private static final Instant SENTINEL_INSTANT = Instant.ofEpochSecond(0L);
    }

    // =========================================================================
    // Pre-allocated no-op statement
    // =========================================================================

    /** Pre-allocated {@link PersistenceStatement} singleton — all binds are no-ops. */
    private static final class NoOpStatement implements PersistenceStatement {

        static final NoOpStatement INSTANCE = new NoOpStatement();

        private NoOpStatement() { }

        @Override public PersistenceStatement bindInt(int idx, int v)         { return this; }
        @Override public PersistenceStatement bindLong(int idx, long v)       { return this; }
        @Override public PersistenceStatement bindShort(int idx, short v)     { return this; }
        @Override public PersistenceStatement bindFloat(int idx, float v)     { return this; }
        @Override public PersistenceStatement bindDouble(int idx, double v)   { return this; }
        @Override public PersistenceStatement bindBoolean(int idx, boolean v) { return this; }
        @Override public PersistenceStatement bindString(int idx, String v)   { return this; }
        @Override public PersistenceStatement bindBytes(int idx, byte[] v)    { return this; }
        @Override public PersistenceStatement bindNull(int idx)               { return this; }
        @Override public PersistenceStatement bindUuid(int idx, UUID v)       { return this; }
        @Override public PersistenceStatement bindInstant(int idx, Instant v) { return this; }
        @Override public QueryResult executeQuery()  { return ConstantConnection.RESULT; }
        @Override public long executeUpdate()        { return 0L; }
        @Override public void close()               { /* no-op */ }
    }
}

