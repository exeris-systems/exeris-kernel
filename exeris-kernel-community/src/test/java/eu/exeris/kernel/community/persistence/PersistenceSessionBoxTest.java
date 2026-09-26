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
import eu.exeris.kernel.spi.security.StorageContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.sql.Connection;
import java.util.Map;
import java.util.Optional;

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
    @DisplayName("whether the session was acquired for a context that declares no tenant")
    class SystemScopeAtAcquire {

        private final ImmutableStorageContext tenant = ImmutableStorageContext.shared("tenant-a");

        private PersistenceSessionBox box() {
            return new PersistenceSessionBox(engine, TransactionIsolation.READ_COMMITTED, true);
        }

        private static PersistenceConnection backing() {
            return mock(PersistenceConnection.class);
        }

        private static void acquireFor(PersistenceSessionBox box, StorageContext context) {
            box.getOrAcquireIfScopeMatches("scope", context, SystemScopeAtAcquire::backing);
        }

        @Test
        @DisplayName("a box that never acquired reports nothing")
        void noAcquireReportsFalse() {
            assertThat(box().acquiredWithSystemScope()).isFalse();
        }

        @Test
        @DisplayName("an acquire for a tenant context is not recorded, with the slot unbound")
        void tenantContextIsNotRecordedWhateverTheSlot() {
            PersistenceSessionBox box = box();

            acquireFor(box, tenant);

            assertThat(box.acquiredWithSystemScope())
                    .as("the context the connection was opened for names a tenant; the empty slot is "
                        + "not what reaches the database")
                    .isFalse();
        }

        @Test
        @DisplayName("an acquire for the system context is recorded, even with a tenant bound")
        void systemContextIsRecordedWhateverTheSlot() {
            PersistenceSessionBox box = box();

            ScopedValue.where(KernelProviders.STORAGE_CONTEXT, tenant).run(
                    () -> acquireFor(box, ImmutableStorageContext.system()));

            assertThat(box.acquiredWithSystemScope()).isTrue();
        }

        @Test
        @DisplayName("a blank isolation key declares no tenant, as it does for the published session key")
        void blankIsolationKeyIsRecorded() {
            PersistenceSessionBox box = box();
            StorageContext blankKey = new ImmutableStorageContext(
                    Optional.of(" "), StorageContext.IsolationStrategy.SHARED,
                    Optional.empty(), Optional.empty(), Optional.empty(), Map.of());

            acquireFor(box, blankKey);

            assertThat(box.acquiredWithSystemScope())
                    .as("a property of the context, not identity with GLOBAL")
                    .isTrue();
        }

        @Test
        @DisplayName("an acquire that names no context is recorded: nothing given to the box declares a tenant")
        void contextlessAcquireIsRecorded() {
            PersistenceSessionBox viaOpener = box();
            PersistenceSessionBox viaScopeKey = box();

            viaOpener.getOrAcquire(SystemScopeAtAcquire::backing);
            viaScopeKey.getOrAcquireIfScopeMatches("shared", SystemScopeAtAcquire::backing);

            assertThat(viaOpener.acquiredWithSystemScope()).isTrue();
            assertThat(viaScopeKey.acquiredWithSystemScope()).isTrue();
        }

        @Test
        @DisplayName("a reuse does not overwrite what the acquire recorded, in either direction")
        void reuseKeepsTheAcquireAnswer() {
            PersistenceSessionBox tenantFirst = box();
            acquireFor(tenantFirst, tenant);
            acquireFor(tenantFirst, ImmutableStorageContext.system());

            PersistenceSessionBox systemFirst = box();
            acquireFor(systemFirst, ImmutableStorageContext.system());
            acquireFor(systemFirst, tenant);

            assertThat(tenantFirst.acquiredWithSystemScope())
                    .as("the connection was opened for a tenant; a later reuse opens nothing")
                    .isFalse();
            assertThat(systemFirst.acquiredWithSystemScope())
                    .as("the connection was opened for the system context; a later reuse does not re-scope it")
                    .isTrue();
        }
    }
}
