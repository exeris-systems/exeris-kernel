/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.persistence;

import eu.exeris.kernel.community.persistence.jdbc.JdbcPersistenceConnection;
import eu.exeris.kernel.spi.context.KernelProviders;
import eu.exeris.kernel.spi.persistence.PersistenceConnection;
import eu.exeris.kernel.spi.persistence.PersistenceEngine;
import eu.exeris.kernel.spi.persistence.TransactionIsolation;
import eu.exeris.kernel.spi.security.ImmutableStorageContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.sql.Connection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
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

    @Nested
    @DisplayName("whether the session was acquired with a StorageContext bound")
    class StorageContextAtAcquire {

        private final ImmutableStorageContext tenant = ImmutableStorageContext.shared("tenant-a");

        private PersistenceSessionBox box() {
            return new PersistenceSessionBox(engine, TransactionIsolation.READ_COMMITTED, true);
        }

        private static PersistenceConnection backing() {
            return mock(PersistenceConnection.class);
        }

        @Test
        @DisplayName("a box that never acquired reports nothing")
        void noAcquireReportsFalse() {
            assertThat(box().acquiredWithoutStorageContext()).isFalse();
        }

        @Test
        @DisplayName("an acquire with the slot unbound is recorded")
        void unboundAcquireIsRecorded() {
            PersistenceSessionBox box = box();

            box.getOrAcquire(StorageContextAtAcquire::backing);

            assertThat(box.acquiredWithoutStorageContext()).isTrue();
        }

        @Test
        @DisplayName("an acquire with the slot bound is not recorded")
        void boundAcquireIsNotRecorded() {
            PersistenceSessionBox box = box();

            ScopedValue.where(KernelProviders.STORAGE_CONTEXT, tenant).run(
                    () -> box.getOrAcquire(StorageContextAtAcquire::backing));

            assertThat(box.acquiredWithoutStorageContext()).isFalse();
        }

        @Test
        @DisplayName("the scope-key acquire path records the same way")
        void scopeKeyAcquireIsRecorded() {
            PersistenceSessionBox box = box();

            box.getOrAcquireIfScopeMatches("shared", StorageContextAtAcquire::backing);

            assertThat(box.acquiredWithoutStorageContext()).isTrue();
        }

        @Test
        @DisplayName("a reuse does not overwrite what the acquire recorded, in either direction")
        void reuseKeepsTheAcquireAnswer() {
            PersistenceSessionBox boundFirst = box();
            ScopedValue.where(KernelProviders.STORAGE_CONTEXT, tenant).run(
                    () -> boundFirst.getOrAcquire(StorageContextAtAcquire::backing));
            boundFirst.getOrAcquire(StorageContextAtAcquire::backing);

            PersistenceSessionBox unboundFirst = box();
            unboundFirst.getOrAcquire(StorageContextAtAcquire::backing);
            ScopedValue.where(KernelProviders.STORAGE_CONTEXT, tenant).run(
                    () -> unboundFirst.getOrAcquire(StorageContextAtAcquire::backing));

            assertThat(boundFirst.acquiredWithoutStorageContext())
                    .as("the connection was acquired scoped; a later unscoped reuse opens nothing")
                    .isFalse();
            assertThat(unboundFirst.acquiredWithoutStorageContext())
                    .as("the connection was acquired unscoped; binding later does not re-scope it")
                    .isTrue();
        }
    }
}
