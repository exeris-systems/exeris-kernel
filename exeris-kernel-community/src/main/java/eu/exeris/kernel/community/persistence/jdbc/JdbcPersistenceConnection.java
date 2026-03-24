/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.community.persistence.jdbc;

import eu.exeris.kernel.core.persistence.PersistenceErrorTranslator;
import eu.exeris.kernel.spi.exceptions.persistence.PersistenceProviderException;
import eu.exeris.kernel.spi.persistence.PersistenceConnection;
import eu.exeris.kernel.spi.persistence.PersistenceStatement;
import eu.exeris.kernel.spi.persistence.QueryResult;
import eu.exeris.kernel.spi.persistence.TransactionIsolation;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.regex.Pattern;

/**
 * Community: {@link PersistenceConnection} backed by a JDBC {@link Connection}.
 *
 * <h2>Transaction Control</h2>
 * <p>Auto-commit is always set to {@code false} on checkout from HikariCP pool.
 * The caller is responsible for explicit {@link #commit()} / {@link #rollback()}.
 * Closing an open transaction via {@link #close()} triggers a rollback.
 *
 * <h2>The Wall (Open-Core)</h2>
 * <p>No reference to off-heap buffers, io_uring, or any Enterprise class.
 * This is the free-tier implementation — correct, not fast.
 *
 * @since 0.5.0
 */
@SuppressWarnings({"PMD.TooManyMethods", "PMD.CyclomaticComplexity"})
public final class JdbcPersistenceConnection implements PersistenceConnection {

    /** Precompiled pattern for PostgreSQL-style {@code $N} parameter placeholders. */
    private static final Pattern PARAM_PLACEHOLDER = Pattern.compile("\\$\\d+");

    private final Connection conn;
    private boolean inTransaction;
    private boolean closed;

    public JdbcPersistenceConnection(Connection conn) throws SQLException {
        this.conn          = conn;
        this.inTransaction = false;
        this.closed        = false;
        conn.setAutoCommit(false);
    }

    @Override
    public PersistenceStatement prepare(String sql) {
        ensureOpen();
        try {
            PreparedStatement preparedStmt = conn.prepareStatement(translateParams(sql));
            return new JdbcPersistenceStatement(preparedStmt);
        } catch (SQLException sqlEx) {
            throw mapSql(sqlEx);
        }
    }

    @Override
    public QueryResult executeQuery(String sql) {
        ensureOpen();
        Statement stmt = null;
        try {
            stmt = conn.createStatement();
            stmt.execute(sql);
            return new JdbcQueryResult(stmt.getResultSet(), stmt);
        } catch (SQLException sqlEx) {
            if (stmt != null) {
                try {
                    stmt.close();
                } catch (SQLException ignored) {
                    // Best-effort cleanup on failure
                }
            }
            throw mapSql(sqlEx);
        }
    }

    @Override
    public long executeUpdate(String sql) {
        ensureOpen();
        try (Statement stmt = conn.createStatement()) {
            return stmt.executeLargeUpdate(sql);
        } catch (SQLException sqlEx) {
            throw mapSql(sqlEx);
        }
    }

    // =========================================================================
    // Transaction Control
    // =========================================================================

    @Override
    public void beginTransaction() {
        beginTransaction(TransactionIsolation.READ_COMMITTED, false);
    }

    @Override
    public void beginTransaction(TransactionIsolation isolation, boolean readOnly) {
        ensureOpen();
        if (inTransaction) {
            throw PersistenceProviderException.queryFailed(
                    "25001", "Transaction already active — commit or rollback first", null);
        }
        try {
            conn.setTransactionIsolation(toJdbcLevel(isolation));
            conn.setReadOnly(readOnly);
            inTransaction = true;
        } catch (SQLException sqlEx) {
            throw mapSql(sqlEx);
        }
    }

    @Override
    public void commit() {
        ensureOpen();
        try {
            conn.commit();
            inTransaction = false;
        } catch (SQLException sqlEx) {
            throw mapSql(sqlEx);
        }
    }

    @Override
    public void rollback() {
        ensureOpen();
        try {
            conn.rollback();
            inTransaction = false;
        } catch (SQLException sqlEx) {
            throw mapSql(sqlEx);
        }
    }

    @Override
    public boolean inTransaction() {
        return inTransaction;
    }

    // =========================================================================
    // Lifecycle
    // =========================================================================

    @Override
    public boolean isOpen() {
        if (closed) {
            return false;
        }
        try {
            return !conn.isClosed();
        } catch (SQLException sqlEx) {
            return false;
        }
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        if (inTransaction) {
            try {
                conn.rollback();
            } catch (SQLException ignored) {
                // Best-effort rollback
            }
            inTransaction = false;
        }
        try {
            conn.close();
        } catch (SQLException ignored) {
            // Best-effort close
        }
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    /**
     * Translates SPI PostgreSQL-style {@code $1, $2} parameter placeholders to
     * JDBC {@code ?} placeholders — required for non-PG JDBC drivers (e.g., H2).
     */
    /* default */ static String translateParams(String sql) {
        return PARAM_PLACEHOLDER.matcher(sql).replaceAll("?");
    }

    private static int toJdbcLevel(TransactionIsolation isolation) {
        return switch (isolation) {
            case READ_UNCOMMITTED -> Connection.TRANSACTION_READ_UNCOMMITTED;
            case READ_COMMITTED   -> Connection.TRANSACTION_READ_COMMITTED;
            case REPEATABLE_READ  -> Connection.TRANSACTION_REPEATABLE_READ;
            case SERIALIZABLE     -> Connection.TRANSACTION_SERIALIZABLE;
        };
    }

    private void ensureOpen() {
        if (closed) {
            throw new IllegalStateException("JdbcPersistenceConnection is closed");
        }
    }

    private static PersistenceProviderException mapSql(SQLException sqlEx) {
        return PersistenceErrorTranslator.translate(sqlEx.getSQLState(), sqlEx.getMessage(), sqlEx);
    }
}

