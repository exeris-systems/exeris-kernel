/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.persistence.jdbc;

import eu.exeris.kernel.spi.config.ConfigProvider;
import eu.exeris.kernel.spi.context.KernelProviders;

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
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

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

    /** Config key for the SQL placeholder-translation cache bound. */
    /* default */ static final String SQL_TRANSLATION_CACHE_KEY = "persistence.sqlTranslationCacheMaxEntries";

    /** Bound applied when nothing is configured. */
    /* default */ static final int DEFAULT_SQL_TRANSLATION_CACHE_MAX_ENTRIES = 1_024;

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
            // Not conditional on inTransaction. The pool baseline is autoCommit=false, so EVERY
            // statement opens a real transaction whether or not an SPI caller opened one — which is
            // why commitStandaloneWriteIfNeeded exists for the success path. Its counterpart was
            // missing here: a standalone write that THREW got neither the commit nor a rollback, so
            // the physical connection went back to the pool inside an aborted transaction and the
            // next request to receive it died on its first statement, whatever that statement was.
            // An RLS WITH CHECK rejection is the ordinary way to reach that — the security control
            // working as designed poisoned a pooled connection for an unrelated later request.
            rollbackQuietly();
            inTransaction = false;
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
     * Unwraps to the backing {@link Connection} for the JDBC compatibility bridge.
     *
     * <p>SPI seam (ADR-017): exposes the underlying {@code java.sql.Connection}
     * via the tier-blind {@link PersistenceConnection#unwrap(Class)} contract,
     * so integration bridges can reach the driver connection even through
     * request-scoped forwarding wrappers — without the SPI naming JDBC.
     * Falls back to the default behaviour ({@code this} when assignable) for
     * any other requested type.
     *
     * <p>Ownership is not transferred: the returned {@link Connection} stays
     * owned by this instance; callers MUST NOT close it directly.
     *
     * @since 0.8.1
     */
    @Override
    public <T> Optional<T> unwrap(Class<T> type) {
        // Exact match only: a request for java.sql.Connection reaches the backing driver
        // connection. Supertype requests (Wrapper, AutoCloseable) intentionally fall through
        // to the default, which resolves to the wrapper itself — never the raw connection.
        if (type == Connection.class) {
            return Optional.of(type.cast(conn));
        }
        return PersistenceConnection.super.unwrap(type);
    }

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

    /**
     * Resolves the translation-cache bound from configuration.
     *
     * <p>Package-private because the bound is invisible from {@code translateParams}: translation
     * is deterministic, so a cached and an uncached result are the same string. Testing the
     * decision therefore has to reach the decision, not the output.
     *
     * @return the configured bound, or the default when nothing is bound or set
     * @throws IllegalArgumentException on a negative value, refused rather than corrected, because
     *         silently substituting the default leaves a misconfigured deployment believing it set
     *         something
     */
    /* default */ static int resolveSqlTranslationCacheMaxEntries() {
        int configured = readConfiguredCacheMaxEntries();
        if (configured < 0) {
            throw refusedCacheBound(configured);
        }
        return configured;
    }

    /** The configured value, unvalidated — a negative one is carried out so the caller can keep it. */
    private static int readConfiguredCacheMaxEntries() {
        if (!KernelProviders.CURRENT_CONFIG.isBound()) {
            return DEFAULT_SQL_TRANSLATION_CACHE_MAX_ENTRIES;
        }
        ConfigProvider config = KernelProviders.CURRENT_CONFIG.get();
        return config == null
                ? DEFAULT_SQL_TRANSLATION_CACHE_MAX_ENTRIES
                : config.getIntOrDefault(SQL_TRANSLATION_CACHE_KEY, DEFAULT_SQL_TRANSLATION_CACHE_MAX_ENTRIES);
    }

    private static IllegalArgumentException refusedCacheBound(int configured) {
        return new IllegalArgumentException(
                SQL_TRANSLATION_CACHE_KEY + " must be >= 0 (0 disables caching), got: " + configured);
    }

    private static final class SqlPlaceholderTranslator {
        /**
         * Unresolved marker. {@code Integer.MIN_VALUE} rather than {@code -1} so that any OTHER
         * negative in this field is the refused configured value itself — which is what lets a
         * misconfigured deployment fail identically on every statement without re-resolving.
         */
        private static final int CACHE_LIMIT_UNRESOLVED = Integer.MIN_VALUE;

        /**
         * Compute-once via CAS rather than double-checked locking, per CONTRIBUTING's lazy-init
         * rule. A benign race resolves twice and stores the same value; the alternative — reading
         * the provider on every translate — would put a config lookup on the statement path.
         */
        private static final AtomicInteger CACHE_LIMIT = new AtomicInteger(CACHE_LIMIT_UNRESOLVED);

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

        /**
         * The memoization bound, resolved once from configuration.
         *
         * <p><b>This cache never evicts.</b> It fills with the first N distinct statements the
         * process happens to see and then stops admitting — so on an application with more than N
         * statements the resident set is the earliest ones, not the hottest, and every other
         * statement is re-translated on every call. That is why the bound is worth configuring
         * rather than merely worth having: raising it is the only lever, and until 0.12 there was
         * none.
         *
         * <p>{@code 0} disables caching entirely — a coherent setting for a workload with
         * unbounded statement variety, where the cache is pure overhead. A negative value is
         * refused rather than corrected, because silently substituting the default would leave a
         * misconfigured deployment believing it had set something.
         *
         * @return the maximum number of cached translations
         * @throws IllegalArgumentException on a negative configured value
         */
        private static int cacheMaxEntries() {
            int cached = CACHE_LIMIT.get();
            if (cached == CACHE_LIMIT_UNRESOLVED) {
                CACHE_LIMIT.compareAndSet(CACHE_LIMIT_UNRESOLVED, readConfiguredCacheMaxEntries());
                cached = CACHE_LIMIT.get();
            }
            if (cached < 0) {
                // The refused value itself, remembered. translateParams runs from prepareStatement
                // on every statement, so re-resolving would make a misconfigured deployment pay a
                // provider lookup per statement to be told the same thing. It is broken either way;
                // it should not be slow about it.
                throw refusedCacheBound(cached);
            }
            return cached;
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
            if (TRANSLATED_SQL_CACHE.size() < cacheMaxEntries()) {
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

    /**
     * Rolls back whatever transaction the physical connection is in, if any, swallowing failure.
     *
     * <p>Asks the driver rather than this object's own {@code inTransaction} flag: that flag tracks
     * SPI-level transactions, and the pool baseline of {@code autoCommit=false} means a caller who
     * never opened one is still inside a database transaction.
     */
    private void rollbackQuietly() {
        try {
            if (!conn.getAutoCommit()) {
                conn.rollback();
            }
        } catch (SQLException _) {
            // Best-effort: the connection is being discarded to the pool either way, and a rollback
            // that cannot run leaves it no worse than not attempting one.
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

