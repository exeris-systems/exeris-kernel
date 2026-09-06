/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.persistence.jdbc;

import eu.exeris.kernel.core.persistence.PersistenceErrorTranslator;
import eu.exeris.kernel.spi.exceptions.persistence.PersistenceProviderException;
import eu.exeris.kernel.spi.persistence.QueryResult;
import eu.exeris.kernel.spi.persistence.RowCursor;

import java.lang.foreign.MemorySegment;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Community: {@link QueryResult} and {@link RowCursor} backed by a JDBC {@link ResultSet}.
 *
 * <p>columnCount is read ONCE at construction — not per-row.
 * Transport and auth are handled entirely by the JDBC driver (pgjdbc).
 * Community does NOT implement its own wire protocol.
 *
 * @since 0.5
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

    /* default */ static JdbcQueryResult execute(Connection conn, String sql) throws SQLException {
        try (StatementOwner owner = StatementOwner.open(conn)) {
            return new JdbcQueryResult(owner.statement().executeQuery(sql), owner.release());
        }
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
        ensureOpen();
        try {
            return resultSet.getStatement() != null
                    ? resultSet.getStatement().getUpdateCount()
                    : -1;
        } catch (SQLException _) {
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
        } catch (SQLException _) {
            // Best-effort
        }
        if (owner != null) {
            try {
                owner.close();
            } catch (SQLException _) {
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

    private static final class StatementOwner implements AutoCloseable {
        private final Statement statement;
        private boolean released;

        private StatementOwner(Statement statement) {
            this.statement = statement;
            this.released = false;
        }

        private static StatementOwner open(Connection conn) throws SQLException {
            return new StatementOwner(conn.createStatement());
        }

        private Statement statement() {
            return statement;
        }

        private Statement release() {
            released = true;
            return statement;
        }

        @Override
        public void close() {
            if (!released) {
                try {
                    statement.close();
                } catch (SQLException _) {
                    // Best-effort
                }
            }
        }
    }

    /**
     * Community: {@link RowCursor} over a single {@link ResultSet}, implementing the index,
     * SQL-NULL and declared-type-domain rules of ADR-080 (see {@code docs/subsystems/persistence.md}).
     *
     * <p>One instance is shared by every row a {@link JdbcQueryResult} advances through — it is
     * a flyweight, not a per-row object — and {@link #getString} is the only accessor whose
     * per-column rendering verdict is cached, lazily, the first time that column is read.
     */
    @SuppressWarnings({"PMD.CyclomaticComplexity", "PMD.TooManyMethods"})
    /* default */ static final class JdbcRowCursor implements RowCursor {

        private static final String NULL_COL_PREFIX = "column ";
        private static final String NULL_COL_SUFFIX = " is SQL NULL";

        private static final byte COLUMN_UNRESOLVED = 0;
        private static final byte COLUMN_RENDERABLE = 1;
        private static final byte COLUMN_REFUSED    = 2;

        private final ResultSet resultSet;
        private final int       cachedColumnCount;

        /**
         * Per-column {@code getString} verdicts, allocated on the first {@code getString} call and
         * never on the primitive or segment paths. Result metadata is fixed for the life of a
         * result set, so each column is resolved once.
         */
        private byte[] columnPolicies;
        /** Whether this connection's engine is one whose rendering ADR-080 §2 contracts. */
        private boolean renderingIsContracted;

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
            return MemorySegment.ofArray(bytes).asReadOnly();
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
                if (columnPolicy(column) == COLUMN_REFUSED) {
                    throw PersistenceProviderException.unsupportedColumnType(
                            declaredTypeName(column), column, "getString");
                }
                return resultSet.getString(column + 1);
            } catch (SQLException sqlEx) {
                throw mapSql(sqlEx);
            }
        }

        /**
         * Whether {@code getString} may render this column, resolved once per column (ADR-080 §2).
         *
         * <p>Keyed on the <b>declared type name</b>, never on a JDBC type code or an OID range, and
         * that is measured rather than assumed: pgjdbc reports both {@code bool} (renderable) and
         * {@code bit} (not) as {@link java.sql.Types#BIT}, so the code cannot separate them, and a
         * native {@code enum} arrives as {@link java.sql.Types#VARCHAR} carrying the application's
         * own type name, so only the name catches it.
         *
         * <p>The verdict belongs to the column, not the row — a SQL NULL in an unsupported column
         * still refuses, because {@code null} would report "no value here" when the truth is "this
         * column cannot be rendered".
         */
        private byte columnPolicy(int column) throws SQLException {
            if (columnPolicies == null) {
                columnPolicies = new byte[cachedColumnCount];
                renderingIsContracted = PgTypeSet.isContractedEngine(resultSet);
            }
            byte cached = columnPolicies[column];
            if (cached != COLUMN_UNRESOLVED) {
                return cached;
            }
            byte resolved = !renderingIsContracted || PgTypeSet.renders(declaredTypeName(column))
                    ? COLUMN_RENDERABLE
                    : COLUMN_REFUSED;
            columnPolicies[column] = resolved;
            return resolved;
        }

        private String declaredTypeName(int column) throws SQLException {
            ResultSetMetaData meta = resultSet.getMetaData();
            return meta == null ? "" : meta.getColumnTypeName(column + 1);
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

        // ResultSet.getTimestamp returns java.sql.Timestamp by JDBC contract (which extends
        // java.util.Date as a JDBC API artefact). Convert immediately to Instant on the
        // next line — the Timestamp reference is never held beyond the decode and never
        // exposed across the SPI boundary, so the java.time-vs-java.util.Date concern
        // PMD raises does not apply to the call site.
        @SuppressWarnings("PMD.ReplaceJavaUtilDate")
        @Override
        public Instant getInstant(int column) {
            checkBounds(column);
            try {
                Timestamp timestamp = resultSet.getTimestamp(column + 1);
                return timestamp == null ? null : timestamp.toInstant();
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
            } catch (SQLException _) {
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