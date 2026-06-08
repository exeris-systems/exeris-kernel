/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.community.persistence.jdbc;

import eu.exeris.kernel.spi.exceptions.KernelErrorCodes;
import eu.exeris.kernel.spi.exceptions.persistence.PersistenceProviderException;
import eu.exeris.kernel.spi.persistence.TransactionIsolation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * L1 Unit: {@link JdbcPersistenceConnection} — lifecycle, transaction control,
 * and parameter translation, using Mockito-mocked JDBC Connection.
 *
 * <h2>Coverage</h2>
 * <ul>
 *   <li>isOpen() reflects JDBC closed state</li>
 *   <li>beginTransaction() sets JDBC isolation and readOnly flag</li>
 *   <li>Double beginTransaction() throws PersistenceProviderException</li>
 *   <li>commit() clears inTransaction flag, delegates to conn.commit()</li>
 *   <li>rollback() clears inTransaction flag, delegates to conn.rollback()</li>
 *   <li>close() rolls back open transaction before closing connection</li>
 *   <li>double close() is idempotent</li>
 *   <li>translateParams() converts $N placeholders to ?</li>
 *   <li>Operations after close() throw IllegalStateException</li>
 * </ul>
 *
 * @since 0.5.0
 */
@DisplayName("L1 Unit: JdbcPersistenceConnection")
@ExtendWith(MockitoExtension.class)
class JdbcPersistenceConnectionTest {

    @Mock
    Connection mockConn;

    @Mock
    Statement mockStmt;

    @Mock
    PreparedStatement mockPreparedStmt;

    @Mock
    ResultSet mockResultSet;

    @Mock
    ResultSetMetaData mockMetaData;

    private JdbcPersistenceConnection connection;

    @BeforeEach
    void setUp() throws SQLException {
        // Constructor must not force tx mode regardless of the pool baseline.
        connection = new JdbcPersistenceConnection(mockConn);
        verify(mockConn, never()).setAutoCommit(false);
    }

    // =========================================================================
    // isOpen()
    // =========================================================================

    @Nested
    @DisplayName("isOpen()")
    class IsOpen {

        @Test
        @DisplayName("isOpen() returns true when underlying connection is not closed")
        void isOpenTrue() throws SQLException {
            when(mockConn.isClosed()).thenReturn(false);
            assertThat(connection.isOpen()).isTrue();
        }

        @Test
        @DisplayName("isOpen() returns false when underlying JDBC connection is closed")
        void isOpenFalseWhenClosed() throws SQLException {
            when(mockConn.isClosed()).thenReturn(true);
            assertThat(connection.isOpen()).isFalse();
        }

        @Test
        @DisplayName("isOpen() returns false after close()")
        void isOpenFalseAfterClose() throws SQLException {
            // After connection.close(), the 'closed' flag is set to true —
            // isOpen() returns false directly without calling conn.isClosed()
            connection.close();
            assertThat(connection.isOpen()).isFalse();
        }
    }

    // =========================================================================
    // Transaction control
    // =========================================================================

    @Nested
    @DisplayName("Transaction control")
    class TransactionControl {

        @Test
        @DisplayName("beginTransaction(READ_COMMITTED, false) sets correct JDBC isolation level")
        void beginReadCommittedSetsIsolation() throws SQLException {
            when(mockConn.getAutoCommit()).thenReturn(true);
            when(mockConn.getTransactionIsolation()).thenReturn(Connection.TRANSACTION_SERIALIZABLE);
            when(mockConn.isReadOnly()).thenReturn(true);
            connection.beginTransaction(TransactionIsolation.READ_COMMITTED, false);
            verify(mockConn).setAutoCommit(false);
            verify(mockConn).setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
            verify(mockConn).setReadOnly(false);
            assertThat(connection.inTransaction()).isTrue();
        }

        @Test
        @DisplayName("beginTransaction(SERIALIZABLE, true) sets SERIALIZABLE + readOnly")
        void beginSerializableReadOnly() throws SQLException {
            when(mockConn.getAutoCommit()).thenReturn(true);
            when(mockConn.getTransactionIsolation()).thenReturn(Connection.TRANSACTION_READ_COMMITTED);
            when(mockConn.isReadOnly()).thenReturn(false);
            connection.beginTransaction(TransactionIsolation.SERIALIZABLE, true);
            verify(mockConn).setAutoCommit(false);
            verify(mockConn).setTransactionIsolation(Connection.TRANSACTION_SERIALIZABLE);
            verify(mockConn).setReadOnly(true);
            assertThat(connection.inTransaction()).isTrue();
        }

        @Test
        @DisplayName("beginTransaction() skips JDBC setters when isolation/readOnly already match")
        void beginSkipsRedundantJdbcSetters() throws SQLException {
            when(mockConn.getAutoCommit()).thenReturn(false);
            when(mockConn.getTransactionIsolation()).thenReturn(Connection.TRANSACTION_READ_COMMITTED);
            when(mockConn.isReadOnly()).thenReturn(false);

            connection.beginTransaction(TransactionIsolation.READ_COMMITTED, false);

            verify(mockConn, never()).setAutoCommit(false);
            verify(mockConn, never()).setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
            verify(mockConn, never()).setReadOnly(false);
            assertThat(connection.inTransaction()).isTrue();
        }

        @Test
        @DisplayName("beginTransaction() enables explicit tx mode when autoCommit=true")
        void beginEnablesExplicitTxMode() throws SQLException {
            when(mockConn.getAutoCommit()).thenReturn(true);
            when(mockConn.getTransactionIsolation()).thenReturn(Connection.TRANSACTION_READ_COMMITTED);
            when(mockConn.isReadOnly()).thenReturn(false);

            connection.beginTransaction(TransactionIsolation.READ_COMMITTED, false);

            verify(mockConn).setAutoCommit(false);
            assertThat(connection.inTransaction()).isTrue();
        }

        @Test
        @DisplayName("double beginTransaction() without commit/rollback throws PersistenceProviderException")
        void doubleBeginThrows() throws SQLException {
            connection.beginTransaction(TransactionIsolation.READ_COMMITTED, false);
            assertThatThrownBy(() ->
                connection.beginTransaction(TransactionIsolation.READ_COMMITTED, false))
                    .isInstanceOf(PersistenceProviderException.class);
        }

        @Test
        @DisplayName("commit() calls conn.commit() and clears inTransaction flag")
        void commitDelegatesToJdbc() throws SQLException {
            when(mockConn.getAutoCommit()).thenReturn(true, false);
            when(mockConn.getTransactionIsolation()).thenReturn(Connection.TRANSACTION_READ_COMMITTED);
            when(mockConn.isReadOnly()).thenReturn(false);
            connection.beginTransaction();
            connection.commit();
            verify(mockConn).commit();
            verify(mockConn).setAutoCommit(true);
            assertThat(connection.inTransaction()).isFalse();
        }

        @Test
        @DisplayName("rollback() calls conn.rollback() and clears inTransaction flag")
        void rollbackDelegatesToJdbc() throws SQLException {
            when(mockConn.getAutoCommit()).thenReturn(true, false);
            when(mockConn.getTransactionIsolation()).thenReturn(Connection.TRANSACTION_READ_COMMITTED);
            when(mockConn.isReadOnly()).thenReturn(false);
            connection.beginTransaction();
            connection.rollback();
            verify(mockConn).rollback();
            verify(mockConn).setAutoCommit(true);
            assertThat(connection.inTransaction()).isFalse();
        }

        @Test
        @DisplayName("commit() restores baseline JDBC settings including isolation")
        void commitRestoresBaselineSettings() throws SQLException {
            when(mockConn.getAutoCommit()).thenReturn(true);
            when(mockConn.getTransactionIsolation()).thenReturn(Connection.TRANSACTION_SERIALIZABLE);
            when(mockConn.isReadOnly()).thenReturn(false);

            connection.beginTransaction(TransactionIsolation.READ_COMMITTED, false);
            connection.commit();

            var order = inOrder(mockConn);
            order.verify(mockConn).setAutoCommit(false);
            order.verify(mockConn).setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
            order.verify(mockConn).commit();
            order.verify(mockConn).setTransactionIsolation(Connection.TRANSACTION_SERIALIZABLE);
            order.verify(mockConn).setReadOnly(false);
            order.verify(mockConn).setAutoCommit(true);
        }

        @Test
        @DisplayName("rollback() restores baseline JDBC settings including isolation")
        void rollbackRestoresBaselineSettings() throws SQLException {
            when(mockConn.getAutoCommit()).thenReturn(true);
            when(mockConn.getTransactionIsolation()).thenReturn(Connection.TRANSACTION_SERIALIZABLE);
            when(mockConn.isReadOnly()).thenReturn(false);

            connection.beginTransaction(TransactionIsolation.READ_COMMITTED, false);
            connection.rollback();

            var order = inOrder(mockConn);
            order.verify(mockConn).setAutoCommit(false);
            order.verify(mockConn).setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
            order.verify(mockConn).rollback();
            order.verify(mockConn).setTransactionIsolation(Connection.TRANSACTION_SERIALIZABLE);
            order.verify(mockConn).setReadOnly(false);
            order.verify(mockConn).setAutoCommit(true);
        }

        @Test
        @DisplayName("non-transactional updates commit on baseline autoCommit=false without breaking explicit tx")
        void nonTransactionalUpdatesCommitOnBaselineAutoCommitFalse() throws SQLException {
            when(mockConn.getAutoCommit()).thenReturn(false);
            when(mockConn.getTransactionIsolation()).thenReturn(Connection.TRANSACTION_READ_COMMITTED);
            when(mockConn.isReadOnly()).thenReturn(false);
            when(mockConn.createStatement()).thenReturn(mockStmt);
            when(mockStmt.executeLargeUpdate("UPDATE docs SET value = 'x'"))
                    .thenReturn(1L);
            when(mockConn.prepareStatement("INSERT INTO docs(value) VALUES (?)"))
                    .thenReturn(mockPreparedStmt);
            when(mockPreparedStmt.executeLargeUpdate()).thenReturn(1L);

            assertThat(connection.executeUpdate("UPDATE docs SET value = 'x'"))
                    .isEqualTo(1L);
            verify(mockConn, times(1)).commit();
            verify(mockConn, never()).rollback();
            verify(mockConn, never()).setAutoCommit(true);

            try (var stmt = connection.prepare("INSERT INTO docs(value) VALUES ($1)")) {
                assertThat(stmt.bindString(0, "tenant-a").executeUpdate()).isEqualTo(1L);
            }
            verify(mockConn, times(2)).commit();
            verify(mockConn, never()).rollback();
            verify(mockConn, never()).setAutoCommit(true);

            connection.beginTransaction();
            assertThat(connection.inTransaction()).isTrue();

            try (var stmt = connection.prepare("INSERT INTO docs(value) VALUES ($1)")) {
                assertThat(stmt.bindString(0, "tenant-b").executeUpdate()).isEqualTo(1L);
            }
            verify(mockConn, times(2)).commit();
            verify(mockConn, never()).rollback();

            connection.commit();
            verify(mockConn, times(3)).commit();
            verify(mockConn, never()).setAutoCommit(true);
            assertThat(connection.inTransaction()).isFalse();
        }

        @Test
        @DisplayName("inTransaction() returns false initially")
        void inTransactionFalseInitially() {
            assertThat(connection.inTransaction()).isFalse();
        }
    }

    // =========================================================================
    // close() lifecycle
    // =========================================================================

    @Nested
    @DisplayName("close() lifecycle")
    class CloseLifecycle {

        @Test
        @DisplayName("close() with open transaction calls rollback() before conn.close()")
        void closeRollsBackOpenTransaction() throws SQLException {
            connection.beginTransaction();
            connection.close();
            verify(mockConn).rollback();
            verify(mockConn).close();
            assertThat(connection.isOpen()).isFalse();
        }

        @Test
        @DisplayName("close() without transaction calls conn.close() directly")
        void closeWithoutTransactionClosesConn() throws SQLException {
            connection.close();
            verify(mockConn).close();
        }

        @Test
        @DisplayName("close() is idempotent — second call does not throw or invoke JDBC again")
        void closeIsIdempotent() throws SQLException {
            connection.close();
            assertThatCode(() -> connection.close()).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("prepare() after close() throws IllegalStateException")
        void prepareAfterCloseThrows() throws SQLException {
            connection.close();
            assertThatThrownBy(() -> connection.prepare("SELECT 1"))
                    .isInstanceOf(IllegalStateException.class);
        }

        @Test
        @DisplayName("beginTransaction() after close() throws IllegalStateException")
        void beginTransactionAfterCloseThrows() throws SQLException {
            connection.close();
            assertThatThrownBy(() -> connection.beginTransaction())
                    .isInstanceOf(IllegalStateException.class);
        }

        @Test
        @DisplayName("commit() after close() throws IllegalStateException")
        void commitAfterCloseThrows() throws SQLException {
            connection.close();
            assertThatThrownBy(() -> connection.commit())
                    .isInstanceOf(IllegalStateException.class);
        }

        @Test
        @DisplayName("rollback() after close() throws IllegalStateException")
        void rollbackAfterCloseThrows() throws SQLException {
            connection.close();
            assertThatThrownBy(() -> connection.rollback())
                    .isInstanceOf(IllegalStateException.class);
        }

        @Test
        @DisplayName("close() silently handles rollback SQLException (best-effort)")
        void closeIgnoresRollbackSqlException() throws SQLException {
            connection.beginTransaction();
            doThrow(new SQLException("network error")).when(mockConn).rollback();
            // Must not propagate the SQLException
            assertThatCode(() -> connection.close()).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("close() invokes onClose hook exactly once even when called multiple times")
        void closeInvokesOnCloseHookExactlyOnce() throws SQLException {
            AtomicInteger hookCalls = new AtomicInteger(0);
            JdbcPersistenceConnection local = new JdbcPersistenceConnection(mockConn, hookCalls::incrementAndGet);

            local.close();
            local.close();

            assertThat(hookCalls.get()).isEqualTo(1);
            verify(mockConn, times(1)).close();
        }

        @Test
        @DisplayName("concurrent close race invokes onClose hook exactly once")
        void concurrentCloseRaceInvokesOnCloseHookOnce() throws Exception {
            AtomicInteger hookCalls = new AtomicInteger(0);
            JdbcPersistenceConnection local = new JdbcPersistenceConnection(mockConn, hookCalls::incrementAndGet);
            CountDownLatch start = new CountDownLatch(1);

            Thread t1 = Thread.ofVirtual().start(() -> {
                await(start);
                local.close();
            });
            Thread t2 = Thread.ofVirtual().start(() -> {
                await(start);
                local.close();
            });

            start.countDown();
            t1.join();
            t2.join();

            assertThat(hookCalls.get()).isEqualTo(1);
            verify(mockConn, times(1)).close();
        }
    }

    // =========================================================================
    // executeQuery() lifecycle
    // =========================================================================

    @Nested
    @DisplayName("executeQuery() lifecycle")
    class ExecuteQueryLifecycle {

        @Test
        @DisplayName("returned QueryResult keeps Statement open until result.close()")
        void queryResultOwnsStatementLifecycle() throws SQLException {
            when(mockConn.createStatement()).thenReturn(mockStmt);
            when(mockStmt.executeQuery("SELECT 1")).thenReturn(mockResultSet);
            when(mockResultSet.getMetaData()).thenReturn(mockMetaData);
            when(mockMetaData.getColumnCount()).thenReturn(1);
            when(mockResultSet.next()).thenReturn(true, false);
            when(mockResultSet.getInt(1)).thenReturn(1);

            try (var result = connection.executeQuery("SELECT 1")) {
                assertThat(result.next()).isTrue();
                assertThat(result.row().getInt(0)).isEqualTo(1);
            }

            verify(mockStmt).close();
            verify(mockResultSet).close();
        }
    }

    // =========================================================================
    // SQLSTATE translation via Core translator
    // =========================================================================

    @Nested
    @DisplayName("SQLSTATE translation")
    class SqlStateTranslation {

        @Test
        @DisplayName("SQLSTATE 40001 maps via translator with [RETRYABLE] detail")
        void sqlState40001IsRetryable() throws SQLException {
            when(mockConn.createStatement()).thenReturn(mockStmt);
            when(mockStmt.executeLargeUpdate("UPDATE test SET v = 1"))
                    .thenThrow(new SQLException("could not serialize access", "40001"));

            assertThatThrownBy(() -> connection.executeUpdate("UPDATE test SET v = 1"))
                    .isInstanceOf(PersistenceProviderException.class)
                    .satisfies(ex -> {
                        PersistenceProviderException ppe = (PersistenceProviderException) ex;
                        assertThat(ppe.errorCode()).isEqualTo(KernelErrorCodes.EX_PERS_5003);
                        assertThat(String.valueOf(ppe.rawArgs()[1])).contains("[RETRYABLE]");
                    });
        }

        @Test
        @DisplayName("SQLSTATE 28P01 maps to EX_PERS_5004")
        void sqlState28P01MapsToAuthFailure() throws SQLException {
            doThrow(new SQLException("password authentication failed", "28P01"))
                .when(mockConn).prepareStatement("SELECT 1");

            assertThatThrownBy(() -> connection.prepare("SELECT 1"))
                    .isInstanceOf(PersistenceProviderException.class)
                    .satisfies(ex -> {
                        PersistenceProviderException ppe = (PersistenceProviderException) ex;
                        assertThat(ppe.errorCode()).isEqualTo(KernelErrorCodes.EX_PERS_5004);
                        assertThat(ppe.rawArgs()[0]).isEqualTo("28P01");
                    });
        }
    }

    // =========================================================================
    // translateParams() — static helper
    // =========================================================================

    @Nested
    @DisplayName("translateParams() — placeholder conversion")
    class TranslateParams {

        @Test
        @DisplayName("$1, $2 are replaced with ?")
        void singlePlaceholders() {
            String result = JdbcPersistenceConnection.translateParams(
                    "SELECT * FROM users WHERE id = $1 AND name = $2");
            assertThat(result).isEqualTo("SELECT * FROM users WHERE id = ? AND name = ?");
        }

        @Test
        @DisplayName("$10 is replaced with a single ?")
        void twoDigitPlaceholder() {
            String result = JdbcPersistenceConnection.translateParams(
                    "INSERT INTO t VALUES ($10)");
            assertThat(result).isEqualTo("INSERT INTO t VALUES (?)");
        }

        @Test
        @DisplayName("SQL without placeholders is returned unchanged")
        void noPlaceholdersUnchanged() {
            String sql = "SELECT 1";
            assertThat(JdbcPersistenceConnection.translateParams(sql)).isEqualTo(sql);
        }

        @Test
        @DisplayName("empty string returns empty string")
        void emptyStringReturnsEmpty() {
            assertThat(JdbcPersistenceConnection.translateParams("")).isEmpty();
        }

        @Test
        @DisplayName("null input returns null")
        void nullReturnsNull() {
            assertThat(JdbcPersistenceConnection.translateParams(null)).isNull();
        }

        @Test
        @DisplayName("$N inside single quotes is preserved, real placeholders are translated")
        void placeholdersInSingleQuotedLiteralArePreserved() {
            String result = JdbcPersistenceConnection.translateParams(
                    "SELECT '$1' AS literal, id FROM t WHERE id = $2");
            assertThat(result).isEqualTo("SELECT '$1' AS literal, id FROM t WHERE id = ?");
        }

        @Test
        @DisplayName("$N inside double quotes is preserved, real placeholders are translated")
        void placeholdersInDoubleQuotedIdentifierArePreserved() {
            String result = JdbcPersistenceConnection.translateParams(
                    "SELECT \"$1_col\" FROM t WHERE id = $2");
            assertThat(result).isEqualTo("SELECT \"$1_col\" FROM t WHERE id = ?");
        }

        @Test
        @DisplayName("$N inside line and block comments is preserved")
        void placeholdersInCommentsArePreserved() {
            String sql = "SELECT $1 -- keep $2 in comment\n/* keep $3 */ WHERE id = $4";
            String result = JdbcPersistenceConnection.translateParams(sql);
            assertThat(result).isEqualTo("SELECT ? -- keep $2 in comment\n/* keep $3 */ WHERE id = ?");
        }

        @Test
        @DisplayName("$N inside dollar-quoted bodies is preserved")
        void placeholdersInDollarQuotedBodiesArePreserved() {
            String sql = "SELECT $$body $1$$, $2, $tag$keep $3$tag$, $4";
            String result = JdbcPersistenceConnection.translateParams(sql);
            assertThat(result).isEqualTo("SELECT $$body $1$$, ?, $tag$keep $3$tag$, ?");
        }
    }

    @Nested
    @DisplayName("unwrap(Class) — SPI JDBC seam (ADR-017)")
    class Unwrap {

        @Test
        @DisplayName("unwrap(Connection.class) returns the backing JDBC connection")
        void unwrapConnectionReturnsBacking() {
            assertThat(connection.unwrap(Connection.class)).containsSame(mockConn);
        }

        @Test
        @DisplayName("unwrap(PersistenceConnection.class) returns this connection")
        void unwrapSelfTypeReturnsThis() {
            assertThat(connection.unwrap(
                    eu.exeris.kernel.spi.persistence.PersistenceConnection.class))
                    .containsSame(connection);
        }

        @Test
        @DisplayName("unwrap of an unrelated type returns empty")
        void unwrapUnrelatedTypeReturnsEmpty() {
            assertThat(connection.unwrap(String.class)).isEmpty();
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while awaiting test latch", ex);
        }
    }
}


