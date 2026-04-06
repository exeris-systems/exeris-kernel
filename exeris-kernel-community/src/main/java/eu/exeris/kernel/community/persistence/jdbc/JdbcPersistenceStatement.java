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
import eu.exeris.kernel.spi.persistence.PersistenceStatement;
import eu.exeris.kernel.spi.persistence.QueryResult;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.UUID;

/**
 * Community: {@link PersistenceStatement} backed by a JDBC {@link PreparedStatement}.
 *
 * <h2>ADR-010 L0 Fix — Autoboxing Elimination</h2>
 * <p>Replaces the legacy {@code executeQuery(String, Object...)} pattern.
 * Each {@code bind*} method calls the exact typed JDBC setter — no boxing, no varargs.
 *
 * <h2>Parameter Indexing</h2>
 * <p>SPI uses zero-based indices. JDBC is one-based. All methods add {@code 1}.
 *
 * @since 0.5.0
 */
@SuppressWarnings({"PMD.TooManyMethods", "PMD.CyclomaticComplexity"})
final class JdbcPersistenceStatement implements PersistenceStatement {

    private final PreparedStatement prepStmt;
    private boolean closed;

    /* default */ JdbcPersistenceStatement(PreparedStatement prepStmt) {
        this.prepStmt = prepStmt;
        this.closed = false;
    }

    @Override
    public PersistenceStatement bindInt(int index, int value) {
        ensureOpen();
        try {
            prepStmt.setInt(index + 1, value);
            return this;
        } catch (SQLException sqlEx) {
            throw mapSql(sqlEx);
        }
    }

    @Override
    public PersistenceStatement bindLong(int index, long value) {
        ensureOpen();
        try {
            prepStmt.setLong(index + 1, value);
            return this;
        } catch (SQLException sqlEx) {
            throw mapSql(sqlEx);
        }
    }

    @Override
    public PersistenceStatement bindShort(int index, short value) {
        ensureOpen();
        try {
            prepStmt.setShort(index + 1, value);
            return this;
        } catch (SQLException sqlEx) {
            throw mapSql(sqlEx);
        }
    }

    @Override
    public PersistenceStatement bindFloat(int index, float value) {
        ensureOpen();
        try {
            prepStmt.setFloat(index + 1, value);
            return this;
        } catch (SQLException sqlEx) {
            throw mapSql(sqlEx);
        }
    }

    @Override
    public PersistenceStatement bindDouble(int index, double value) {
        ensureOpen();
        try {
            prepStmt.setDouble(index + 1, value);
            return this;
        } catch (SQLException sqlEx) {
            throw mapSql(sqlEx);
        }
    }

    @Override
    public PersistenceStatement bindBoolean(int index, boolean value) {
        ensureOpen();
        try {
            prepStmt.setBoolean(index + 1, value);
            return this;
        } catch (SQLException sqlEx) {
            throw mapSql(sqlEx);
        }
    }

    @Override
    public PersistenceStatement bindString(int index, String value) {
        ensureOpen();
        try {
            if (value == null) {
                prepStmt.setNull(index + 1, Types.VARCHAR);
            } else {
                prepStmt.setString(index + 1, value);
            }
            return this;
        } catch (SQLException sqlEx) {
            throw mapSql(sqlEx);
        }
    }

    @Override
    public PersistenceStatement bindUuid(int index, UUID value) {
        ensureOpen();
        try {
            if (value == null) {
                prepStmt.setNull(index + 1, Types.OTHER);
            } else {
                prepStmt.setObject(index + 1, value);
            }
            return this;
        } catch (SQLException sqlEx) {
            throw mapSql(sqlEx);
        }
    }

    @Override
    public PersistenceStatement bindBytes(int index, byte[] value) {
        ensureOpen();
        try {
            if (value == null) {
                prepStmt.setNull(index + 1, Types.BINARY);
            } else {
                prepStmt.setBytes(index + 1, value);
            }
            return this;
        } catch (SQLException sqlEx) {
            throw mapSql(sqlEx);
        }
    }

    @Override
    public PersistenceStatement bindNull(int index) {
        ensureOpen();
        try {
            prepStmt.setNull(index + 1, Types.NULL);
            return this;
        } catch (SQLException sqlEx) {
            throw mapSql(sqlEx);
        }
    }

    @Override
    public QueryResult executeQuery() {
        ensureOpen();
        try {
            ResultSet resultSet = prepStmt.executeQuery();
            return new JdbcQueryResult(resultSet, prepStmt);
        } catch (SQLException sqlEx) {
            throw mapSql(sqlEx);
        }
    }

    @Override
    public long executeUpdate() {
        ensureOpen();
        try {
            return prepStmt.executeLargeUpdate();
        } catch (SQLException sqlEx) {
            throw mapSql(sqlEx);
        }
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        try {
            prepStmt.close();
        } catch (SQLException ignored) {
            // Best-effort — idempotent per SPI contract
        }
    }

    private void ensureOpen() {
        if (closed) {
            throw new IllegalStateException("JdbcPersistenceStatement is closed");
        }
    }

    private static PersistenceProviderException mapSql(SQLException sqlEx) {
        return PersistenceErrorTranslator.translate(sqlEx.getSQLState(), sqlEx.getMessage(), sqlEx);
    }
}
