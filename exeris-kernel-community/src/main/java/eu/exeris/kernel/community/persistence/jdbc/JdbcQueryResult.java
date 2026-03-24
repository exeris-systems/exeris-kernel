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
import eu.exeris.kernel.spi.persistence.QueryResult;
import eu.exeris.kernel.spi.persistence.RowCursor;

import java.lang.foreign.MemorySegment;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;

/**
 * Community: {@link QueryResult} and {@link RowCursor} backed by a JDBC {@link ResultSet}.
 *
 * <p>columnCount is read ONCE at construction — not per-row.
 * Transport and auth are handled entirely by the JDBC driver (pgjdbc).
 * Community does NOT implement its own wire protocol.
 *
 * @since 0.5.0
 */
final class JdbcQueryResult implements QueryResult {

    private final ResultSet resultSet;
    private final Statement owner;
    private final JdbcRowCursor cursor;
    private final int           columnCount;
    private boolean closed;

    /* default */ JdbcQueryResult(ResultSet resultSet, Statement owner) throws SQLException {
        this.resultSet   = resultSet;
        this.owner       = owner;
        ResultSetMetaData meta = resultSet.getMetaData();
        this.columnCount = meta != null ? meta.getColumnCount() : 0;
        this.cursor      = new JdbcRowCursor(resultSet, this.columnCount);
        this.closed      = false;
    }

    @Override
    public boolean next() {
        ensureOpen();
        try {
            return resultSet.next();
        } catch (SQLException sqlEx) {
            throw mapSqlException(sqlEx);
        }
    }

    @Override
    public RowCursor row() {
        ensureOpen();
        return cursor;
    }

    @Override
    public long rowsAffected() {
        try {
            return resultSet.getStatement() != null
                    ? resultSet.getStatement().getUpdateCount()
                    : -1;
        } catch (SQLException sqlEx) {
            return -1L;
        }
    }

    @Override
    public int columnCount() {
        return columnCount;
    }

    @Override
    public String commandTag() {
        return null;
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        try {
            resultSet.close();
        } catch (SQLException ignored) {
            // Best-effort
        }
        if (owner != null) {
            try {
                owner.close();
            } catch (SQLException ignored) {
                // Best-effort
            }
        }
    }

    private void ensureOpen() {
        if (closed) {
            throw new IllegalStateException("JdbcQueryResult is already closed");
        }
    }

    private static PersistenceProviderException mapSqlException(SQLException sqlEx) {
        return PersistenceErrorTranslator.translate(sqlEx.getSQLState(), sqlEx.getMessage(), sqlEx);
    }

    @SuppressWarnings("PMD.CyclomaticComplexity") // JDBC adapter: every RowCursor method wraps
    // a try/catch for SQLException — total class CC is inherent to the pattern, not a design flaw.
    /* default */ static final class JdbcRowCursor implements RowCursor {

        private final ResultSet resultSet;
        private final int       cachedColumnCount;

        /* default */ JdbcRowCursor(ResultSet resultSet, int columnCount) {
            this.resultSet         = resultSet;
            this.cachedColumnCount = columnCount;
        }

        @Override
        public int getInt(int column) {
            try {
                return resultSet.getInt(column + 1);
            } catch (SQLException sqlEx) {
                throw mapSql(sqlEx);
            }
        }

        @Override
        public long getLong(int column) {
            try {
                return resultSet.getLong(column + 1);
            } catch (SQLException sqlEx) {
                throw mapSql(sqlEx);
            }
        }

        @Override
        public short getShort(int column) {
            try {
                return resultSet.getShort(column + 1);
            } catch (SQLException sqlEx) {
                throw mapSql(sqlEx);
            }
        }

        @Override
        public float getFloat(int column) {
            try {
                return resultSet.getFloat(column + 1);
            } catch (SQLException sqlEx) {
                throw mapSql(sqlEx);
            }
        }

        @Override
        public double getDouble(int column) {
            try {
                return resultSet.getDouble(column + 1);
            } catch (SQLException sqlEx) {
                throw mapSql(sqlEx);
            }
        }

        @Override
        public boolean getBoolean(int column) {
            try {
                return resultSet.getBoolean(column + 1);
            } catch (SQLException sqlEx) {
                throw mapSql(sqlEx);
            }
        }

        @Override
        public boolean isNull(int column) {
            try {
                resultSet.getObject(column + 1);
                return resultSet.wasNull();
            } catch (SQLException sqlEx) {
                throw mapSql(sqlEx);
            }
        }

        @Override
        public MemorySegment getSegment(int column) {
            byte[] bytes = getBytes(column);
            return bytes == null ? MemorySegment.NULL : MemorySegment.ofArray(bytes);
        }

        @Override
        public int getLength(int column) {
            byte[] bytes = getBytes(column);
            return bytes == null ? -1 : bytes.length;
        }

        @Override
        public String getString(int column) {
            try {
                return resultSet.getString(column + 1);
            } catch (SQLException sqlEx) {
                throw mapSql(sqlEx);
            }
        }

        @Override
        public byte[] getBytes(int column) {
            try {
                return resultSet.getBytes(column + 1);
            } catch (SQLException sqlEx) {
                throw mapSql(sqlEx);
            }
        }

        @Override
        public UUID getUuid(int column) {
            try {
                Object obj = resultSet.getObject(column + 1);
                if (obj == null) {
                    return null;
                }
                if (obj instanceof UUID uuid) {
                    return uuid;
                }
                return UUID.fromString(obj.toString());
            } catch (SQLException sqlEx) {
                throw mapSql(sqlEx);
            }
        }

        @Override
        public int columnCount() {
            return cachedColumnCount;
        }

        @Override
        public boolean isValid() {
            try {
                return !resultSet.isClosed()
                        && !resultSet.isBeforeFirst()
                        && !resultSet.isAfterLast();
            } catch (SQLException sqlEx) {
                return false;
            }
        }

        private static PersistenceProviderException mapSql(SQLException sqlEx) {
            return PersistenceErrorTranslator.translate(sqlEx.getSQLState(), sqlEx.getMessage(), sqlEx);
        }
    }
}