/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.community.persistence;

import eu.exeris.kernel.community.persistence.jdbc.JdbcPersistenceConnection;
import eu.exeris.kernel.spi.persistence.PersistenceConnection;
import eu.exeris.kernel.spi.persistence.PersistenceEngine;
import eu.exeris.kernel.spi.persistence.TransactionIsolation;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.sql.Connection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * L1 Unit: {@link PersistenceSessionBox} request-scoped connection wrapping.
 *
 * <p>Pins the ADR-017 JDBC-bridge regression: the per-request forwarding wrapper
 * ({@code NonOwningPersistenceConnection}) MUST forward
 * {@link PersistenceConnection#unwrap(Class)} to its backing connection, so the
 * JDBC compatibility bridge can reach the underlying {@code java.sql.Connection}
 * even when the dispatcher has bound a request session. Closing the wrapper must
 * stay a no-op (lifecycle owned by {@link PersistenceSessionBox#release()}).
 *
 * @since 0.8.1
 */
@DisplayName("L1 Unit: PersistenceSessionBox request-scoped wrapping")
@ExtendWith(MockitoExtension.class)
class PersistenceSessionBoxTest {

    @Mock
    PersistenceEngine engine;

    @Mock
    Connection backingJdbcConnection;

    private PersistenceConnection requestScoped(PersistenceConnection backing) {
        PersistenceSessionBox box = new PersistenceSessionBox(
                engine, TransactionIsolation.READ_COMMITTED, false);
        RequestPersistenceSession session = box.getOrAcquire(() -> backing);
        return box.requestScopedConnection(session);
    }

    @Nested
    @DisplayName("unwrap forwarding through the request-scoped wrapper")
    class UnwrapForwarding {

        @Test
        @DisplayName("unwrap(Connection.class) reaches the backing JDBC connection")
        void unwrapReachesBackingJdbcConnection() {
            JdbcPersistenceConnection backing =
                    new JdbcPersistenceConnection(backingJdbcConnection);

            PersistenceConnection scoped = requestScoped(backing);

            assertThat(scoped).isNotInstanceOf(JdbcPersistenceConnection.class);
            assertThat(scoped.unwrap(Connection.class)).containsSame(backingJdbcConnection);
        }

        @Test
        @DisplayName("unwrap of an unsupported type returns empty (no leak)")
        void unwrapUnsupportedTypeReturnsEmpty() {
            JdbcPersistenceConnection backing =
                    new JdbcPersistenceConnection(backingJdbcConnection);

            PersistenceConnection scoped = requestScoped(backing);

            assertThat(scoped.unwrap(String.class)).isEmpty();
        }
    }

    @Nested
    @DisplayName("non-owning lifecycle")
    class NonOwningLifecycle {

        @Test
        @DisplayName("close() on the request-scoped wrapper does not close the backing")
        void wrapperCloseIsNoOp() {
            JdbcPersistenceConnection backing =
                    new JdbcPersistenceConnection(backingJdbcConnection);

            PersistenceConnection scoped = requestScoped(backing);
            scoped.close();

            assertThat(backing.isOpen()).isTrue();
        }
    }
}
