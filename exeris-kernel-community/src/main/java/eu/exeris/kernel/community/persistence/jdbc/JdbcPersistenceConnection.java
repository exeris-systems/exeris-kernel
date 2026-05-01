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
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Community: {@link PersistenceConnection} backed by a JDBC {@link Connection}.
 *
 * <h2>Transaction Control</h2>
 * <p>Connections start in pool baseline mode ({@code autoCommit=false}).
 * Transactional scopes switch to explicit mode in {@link #beginTransaction()}
 * and restore baseline after {@link #commit()} / {@link #rollback()}.
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

    private final Connection conn;
    private final Runnable onClose;
    private boolean inTransaction;
    private final AtomicBoolean closed;
    private boolean baselineAutoCommit;
    private boolean baselineReadOnly;
    private int baselineIsolation;
    private boolean baselineCaptured;

    public JdbcPersistenceConnection(Connection conn) {
        this(conn, () -> { });
    }

    public JdbcPersistenceConnection(Connection conn, Runnable onClose) {
        this.conn          = Objects.requireNonNull(conn, "conn must not be null");
        this.onClose       = Objects.requireNonNull(onClose, "onClose must not be null");
        this.inTransaction = false;
        this.closed        = new AtomicBoolean(false);
        this.baselineCaptured = false;
    }

    @Override
    public PersistenceStatement prepare(String sql) {
        ensureOpen();
        try {
            PreparedStatement preparedStmt = conn.prepareStatement(translateParams(sql));
            return new JdbcPersistenceStatement(preparedStmt, this::commitStandaloneWriteIfNeeded);
        } catch (SQLException sqlEx) {
            throw mapSql(sqlEx);
        }
    }

    @Override
    public QueryResult executeQuery(String sql) {
        ensureOpen();
        try {
            return JdbcQueryResult.execute(conn, sql);
        } catch (SQLException sqlEx) {
            throw mapSql(sqlEx);
        }
    }

    @Override
    public long executeUpdate(String sql) {
        ensureOpen();
        try (Statement stmt = conn.createStatement()) {
            long updated = stmt.executeLargeUpdate(sql);
            commitStandaloneWriteIfNeeded();
            return updated;
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
            captureBaseline();
            if (baselineAutoCommit) {
                conn.setAutoCommit(false);
            }
            int targetIsolation = toJdbcLevel(isolation);
            if (conn.getTransactionIsolation() != targetIsolation) {
                conn.setTransactionIsolation(targetIsolation);
            }
            if (conn.isReadOnly() != readOnly) {
                conn.setReadOnly(readOnly);
            }
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
            restorePostTransactionBaseline();
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
            restorePostTransactionBaseline();
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
        if (closed.get()) {
            return false;
        }
        try {
            return !conn.isClosed();
        } catch (SQLException _) {
            return false;
        }
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        try {
            if (inTransaction) {
                try {
                    conn.rollback();
                } catch (SQLException _) {
                    // Best-effort rollback
                }
                inTransaction = false;
            }
            try {
                conn.close();
            } catch (SQLException _) {
                // Best-effort close
            }
        } finally {
            onClose.run();
        }
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    /**
     * Returns the raw JDBC {@link Connection} backing this instance.
     *
     * <p><strong>Community infrastructure use only.</strong> Approved callers:
     * <ul>
     *   <li>Pool discard/eviction paths (e.g., {@code CommunityHikariSupport.discardConnection()})</li>
     *   <li>Integration bridges that maintain connection lifecycle externally
     *       (e.g., {@code exeris-spring-runtime-data ExerisDataSource}) — see ADR-012 §6.4</li>
     * </ul>
     * This method is not part of the SPI contract.
     *
     * @return the underlying {@link Connection}; never {@code null}
     */
    public Connection rawJdbcConnection() {
        return conn;
    }

    /**
     * Translates SPI PostgreSQL-style {@code $1, $2} parameter placeholders to
     * JDBC {@code ?} placeholders — required for non-PG JDBC drivers (e.g., H2).
     */
    /* default */ static String translateParams(String sql) {
        return SqlPlaceholderTranslator.translate(sql);
    }

    private static final class SqlPlaceholderTranslator {
        private static final int TRANSLATION_CACHE_MAX_ENTRIES = 1024;
        private static final ConcurrentMap<String, String> TRANSLATED_SQL_CACHE = new ConcurrentHashMap<>();
        private static final char SINGLE_QUOTE = '\'';
        private static final char DOUBLE_QUOTE = '"';
        private static final char DOLLAR = '$';
        private static final char DASH = '-';
        private static final char SLASH = '/';
        private static final char STAR = '*';
        private static final char NEWLINE = '\n';
        private static final char UNDERSCORE = '_';
        private static final char JDBC_PLACEHOLDER = '?';
        private static final String EMPTY_DELIMITER = "";

        private final String sql;
        private int cursor;
        private String activeDollarDelimiter;

        private SqlPlaceholderTranslator(String sql) {
            this.sql = sql;
            this.cursor = 0;
            this.activeDollarDelimiter = EMPTY_DELIMITER;
        }

        private static String translate(String sql) {
            if (sql == null || sql.isEmpty()) {
                return sql;
            }
            String cachedTranslation = TRANSLATED_SQL_CACHE.get(sql);
            if (cachedTranslation != null) {
                return cachedTranslation;
            }

            String translatedSql = new SqlPlaceholderTranslator(sql).translateInternal();
            if (TRANSLATED_SQL_CACHE.size() < TRANSLATION_CACHE_MAX_ENTRIES) {
                String existingTranslation = TRANSLATED_SQL_CACHE.putIfAbsent(sql, translatedSql);
                if (existingTranslation != null) {
                    return existingTranslation;
                }
            }
            return translatedSql;
        }

        private String translateInternal() {
            StringBuilder translatedSql = new StringBuilder(sql.length());
            while (cursor < sql.length()) {
                boolean handled = handleActiveDollarQuotedBody(translatedSql)
                        || handleSingleQuotedString(translatedSql)
                        || handleDoubleQuotedString(translatedSql)
                        || handleLineComment(translatedSql)
                        || handleBlockComment(translatedSql)
                        || handleDollarSequence(translatedSql);
                if (!handled) {
                    translatedSql.append(sql.charAt(cursor));
                    cursor++;
                }
            }
            return translatedSql.toString();
        }

        private boolean handleActiveDollarQuotedBody(StringBuilder translatedSql) {
            if (activeDollarDelimiter.isEmpty()) {
                return false;
            }
            if (sql.startsWith(activeDollarDelimiter, cursor)) {
                translatedSql.append(activeDollarDelimiter);
                cursor += activeDollarDelimiter.length();
                activeDollarDelimiter = EMPTY_DELIMITER;
            } else {
                translatedSql.append(sql.charAt(cursor));
                cursor++;
            }
            return true;
        }

        private boolean handleSingleQuotedString(StringBuilder translatedSql) {
            if (sql.charAt(cursor) != SINGLE_QUOTE) {
                return false;
            }
            cursor = appendSingleQuoted(sql, cursor, translatedSql);
            return true;
        }

        private boolean handleDoubleQuotedString(StringBuilder translatedSql) {
            if (sql.charAt(cursor) != DOUBLE_QUOTE) {
                return false;
            }
            cursor = appendDoubleQuoted(sql, cursor, translatedSql);
            return true;
        }

        private boolean handleLineComment(StringBuilder translatedSql) {
            char currentChar = sql.charAt(cursor);
            char followingChar = nextChar(sql, cursor);
            if (!isLineCommentStart(currentChar, followingChar)) {
                return false;
            }
            cursor = appendLineComment(sql, cursor, translatedSql);
            return true;
        }

        private boolean handleBlockComment(StringBuilder translatedSql) {
            char currentChar = sql.charAt(cursor);
            char followingChar = nextChar(sql, cursor);
            if (!isBlockCommentStart(currentChar, followingChar)) {
                return false;
            }
            cursor = appendBlockComment(sql, cursor, translatedSql);
            return true;
        }

        private boolean handleDollarSequence(StringBuilder translatedSql) {
            if (sql.charAt(cursor) != DOLLAR) {
                return false;
            }
            String delimiter = readDollarQuoteDelimiter(sql, cursor);
            if (!delimiter.isEmpty()) {
                translatedSql.append(delimiter);
                cursor += delimiter.length();
                activeDollarDelimiter = delimiter;
                return true;
            }
            char followingChar = nextChar(sql, cursor);
            if (Character.isDigit(followingChar)) {
                cursor = appendJdbcPlaceholder(sql, cursor, translatedSql);
                return true;
            }
            return false;
        }

        private static char nextChar(String sql, int currentIndex) {
            int nextIndex = currentIndex + 1;
            return nextIndex < sql.length() ? sql.charAt(nextIndex) : '\0';
        }

        private static boolean isLineCommentStart(char currentChar, char nextChar) {
            return currentChar == DASH && nextChar == DASH;
        }

        private static boolean isBlockCommentStart(char currentChar, char nextChar) {
            return currentChar == SLASH && nextChar == STAR;
        }

        private static int appendJdbcPlaceholder(String sql, int placeholderStart, StringBuilder translatedSql) {
            int placeholderCursor = placeholderStart + 2;
            while (placeholderCursor < sql.length() && Character.isDigit(sql.charAt(placeholderCursor))) {
                placeholderCursor++;
            }
            translatedSql.append(JDBC_PLACEHOLDER);
            return placeholderCursor;
        }

        private static int appendSingleQuoted(String sql, int quoteIndex, StringBuilder translatedSql) {
            translatedSql.append(SINGLE_QUOTE);
            int cursor = quoteIndex + 1;
            while (cursor < sql.length()) {
                char currentChar = sql.charAt(cursor);
                translatedSql.append(currentChar);
                cursor++;
                if (currentChar == SINGLE_QUOTE) {
                    boolean escapedQuote = cursor < sql.length() && sql.charAt(cursor) == SINGLE_QUOTE;
                    if (escapedQuote) {
                        translatedSql.append(SINGLE_QUOTE);
                        cursor++;
                    } else {
                        return cursor;
                    }
                }
            }
            return cursor;
        }

        private static int appendDoubleQuoted(String sql, int quoteIndex, StringBuilder translatedSql) {
            translatedSql.append(DOUBLE_QUOTE);
            int cursor = quoteIndex + 1;
            while (cursor < sql.length()) {
                char currentChar = sql.charAt(cursor);
                translatedSql.append(currentChar);
                cursor++;
                if (currentChar == DOUBLE_QUOTE) {
                    boolean escapedQuote = cursor < sql.length() && sql.charAt(cursor) == DOUBLE_QUOTE;
                    if (escapedQuote) {
                        translatedSql.append(DOUBLE_QUOTE);
                        cursor++;
                    } else {
                        return cursor;
                    }
                }
            }
            return cursor;
        }

        private static int appendLineComment(String sql, int commentStart, StringBuilder translatedSql) {
            translatedSql.append("--");
            int cursor = commentStart + 2;
            while (cursor < sql.length()) {
                char currentChar = sql.charAt(cursor);
                translatedSql.append(currentChar);
                cursor++;
                if (currentChar == NEWLINE) {
                    return cursor;
                }
            }
            return cursor;
        }

        private static int appendBlockComment(String sql, int commentStart, StringBuilder translatedSql) {
            translatedSql.append("/*");
            int cursor = commentStart + 2;
            while (cursor < sql.length()) {
                char currentChar = sql.charAt(cursor);
                translatedSql.append(currentChar);
                cursor++;
                boolean blockCommentClosed = currentChar == STAR
                        && cursor < sql.length()
                        && sql.charAt(cursor) == SLASH;
                if (blockCommentClosed) {
                    translatedSql.append(SLASH);
                    return cursor + 1;
                }
            }
            return cursor;
        }

        private static String readDollarQuoteDelimiter(String sql, int delimiterStart) {
            if (sql.charAt(delimiterStart) != DOLLAR) {
                return EMPTY_DELIMITER;
            }
            int delimiterCursor = delimiterStart + 1;
            while (delimiterCursor < sql.length()) {
                char currentChar = sql.charAt(delimiterCursor);
                if (currentChar == DOLLAR) {
                    return sql.substring(delimiterStart, delimiterCursor + 1);
                }
                if (!isDollarTagChar(currentChar)) {
                    return EMPTY_DELIMITER;
                }
                delimiterCursor++;
            }
            return EMPTY_DELIMITER;
        }

        private static boolean isDollarTagChar(char value) {
            return Character.isLetterOrDigit(value) || value == UNDERSCORE;
        }
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
        if (closed.get()) {
            throw new IllegalStateException("JdbcPersistenceConnection is closed");
        }
    }

    private void restorePostTransactionBaseline() throws SQLException {
        if (!baselineCaptured) {
            return;
        }
        conn.setTransactionIsolation(baselineIsolation);
        conn.setReadOnly(baselineReadOnly);
        conn.setAutoCommit(baselineAutoCommit);
        baselineCaptured = false;
    }

    /**
     * Community pool baseline is {@code autoCommit=false}. When SPI callers perform a write
     * without opening an explicit transaction, JDBC still requires a manual commit so the
     * change becomes visible across later pooled connections.
     */
    private void commitStandaloneWriteIfNeeded() throws SQLException {
        if (inTransaction) {
            return;
        }
        if (!conn.getAutoCommit()) {
            conn.commit();
        }
    }

    private void captureBaseline() throws SQLException {
        baselineAutoCommit = conn.getAutoCommit();
        baselineReadOnly = conn.isReadOnly();
        baselineIsolation = conn.getTransactionIsolation();
        baselineCaptured = true;
    }

    private static PersistenceProviderException mapSql(SQLException sqlEx) {
        return PersistenceErrorTranslator.translate(sqlEx.getSQLState(), sqlEx.getMessage(), sqlEx);
    }
}

