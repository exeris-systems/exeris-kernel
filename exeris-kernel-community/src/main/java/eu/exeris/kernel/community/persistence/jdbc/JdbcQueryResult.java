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
import java.util.Objects;
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
    // true only when next() returned true — guards row() contract
    private boolean positioned;

    /* default */ JdbcQueryResult(ResultSet resultSet, Statement owner) throws SQLException {
        this.resultSet   = resultSet;
        this.owner       = owner;
        ResultSetMetaData meta = resultSet.getMetaData();
        this.columnCount = meta != null ? meta.getColumnCount() : 0;
        this.cursor      = new JdbcRowCursor(resultSet, this.columnCount);
        this.closed      = false;
    }

    @Override
    @SuppressWarnings("PMD.CheckResultSet")
    public boolean next() {
        ensureOpen();
        try {
            positioned = resultSet.next();
            return positioned;
        } catch (SQLException sqlEx) {
            positioned = false;
            throw mapSqlException(sqlEx);
        }
    }

    @Override
    public RowCursor row() {
        ensureOpen();
        if (!positioned) {
            throw new IllegalStateException("QueryResult is not positioned on a valid row — call next() first");
        }
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
        ensureOpen();
        return columnCount;
    }

    @Override
    public String commandTag() {
        ensureOpen();
        return null;
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        positioned = false;
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

    @SuppressWarnings({"PMD.CyclomaticComplexity", "PMD.TooManyMethods"})
    /* default */ static final class JdbcRowCursor implements RowCursor {

        private static final String NULL_COL_PREFIX = "column ";
        private static final String NULL_COL_SUFFIX = " is SQL NULL";
        private final ResultSet resultSet;
        private final int       cachedColumnCount;

        /* default */ JdbcRowCursor(ResultSet resultSet, int columnCount) {
            this.resultSet         = resultSet;
            this.cachedColumnCount = columnCount;
        }

        @Override
        public int getInt(int column) {
            checkBounds(column);
            try {
                int value = resultSet.getInt(column + 1);
                if (resultSet.wasNull()) {
                    Objects.requireNonNull(null, NULL_COL_PREFIX + column + NULL_COL_SUFFIX);
                }
                return value;
            } catch (SQLException sqlEx) {
                throw mapSql(sqlEx);
            }
        }

        @Override
        public long getLong(int column) {
            checkBounds(column);
            try {
                long value = resultSet.getLong(column + 1);
                if (resultSet.wasNull()) {
                    Objects.requireNonNull(null, NULL_COL_PREFIX + column + NULL_COL_SUFFIX);
                }
                return value;
            } catch (SQLException sqlEx) {
                throw mapSql(sqlEx);
            }
        }

        @Override
        public short getShort(int column) {
            checkBounds(column);
            try {
                short value = resultSet.getShort(column + 1);
                if (resultSet.wasNull()) {
                    Objects.requireNonNull(null, NULL_COL_PREFIX + column + NULL_COL_SUFFIX);
                }
                return value;
            } catch (SQLException sqlEx) {
                throw mapSql(sqlEx);
            }
        }

        @Override
        public float getFloat(int column) {
            checkBounds(column);
            try {
                float value = resultSet.getFloat(column + 1);
                if (resultSet.wasNull()) {
                    Objects.requireNonNull(null, NULL_COL_PREFIX + column + NULL_COL_SUFFIX);
                }
                return value;
            } catch (SQLException sqlEx) {
                throw mapSql(sqlEx);
            }
        }

        @Override
        public double getDouble(int column) {
            checkBounds(column);
            try {
                double value = resultSet.getDouble(column + 1);
                if (resultSet.wasNull()) {
                    Objects.requireNonNull(null, NULL_COL_PREFIX + column + NULL_COL_SUFFIX);
                }
                return value;
            } catch (SQLException sqlEx) {
                throw mapSql(sqlEx);
            }
        }

        @Override
        public boolean getBoolean(int column) {
            checkBounds(column);
            try {
                boolean value = resultSet.getBoolean(column + 1);
                if (resultSet.wasNull()) {
                    Objects.requireNonNull(null, NULL_COL_PREFIX + column + NULL_COL_SUFFIX);
                }
                return value;
            } catch (SQLException sqlEx) {
                throw mapSql(sqlEx);
            }
        }

        @Override
        public boolean isNull(int column) {
            checkBounds(column);
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
            if (bytes == null) {
                Objects.requireNonNull(null, NULL_COL_PREFIX + column + NULL_COL_SUFFIX);
            }
            return MemorySegment.ofArray(bytes);
        }

        @Override
        public int getLength(int column) {
            byte[] bytes = getBytes(column);
            return bytes == null ? -1 : bytes.length;
        }

        @Override
        public String getString(int column) {
            checkBounds(column);
            try {
                return resultSet.getString(column + 1);
            } catch (SQLException sqlEx) {
                throw mapSql(sqlEx);
            }
        }

        @Override
        public byte[] getBytes(int column) {
            checkBounds(column);
            try {
                return resultSet.getBytes(column + 1);
            } catch (SQLException sqlEx) {
                throw mapSql(sqlEx);
            }
        }

        @Override
        public UUID getUuid(int column) {
            checkBounds(column);
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

        private void checkBounds(int column) {
            if (column < 0 || column >= cachedColumnCount) {
                throw new IndexOutOfBoundsException(
                        NULL_COL_PREFIX + column + " out of bounds [0, " + cachedColumnCount + ")");
            }
        }

        private static PersistenceProviderException mapSql(SQLException sqlEx) {
            return PersistenceErrorTranslator.translate(sqlEx.getSQLState(), sqlEx.getMessage(), sqlEx);
        }
    }
}