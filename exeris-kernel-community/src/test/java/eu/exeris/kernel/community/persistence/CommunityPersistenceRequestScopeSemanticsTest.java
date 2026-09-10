/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.persistence;

import eu.exeris.kernel.spi.persistence.PersistenceConfig;
import eu.exeris.kernel.spi.persistence.TransactionIsolation;
import eu.exeris.kernel.spi.security.ImmutableStorageContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("CommunityPersistenceEngine request-scope connection semantics")
class CommunityPersistenceRequestScopeSemanticsTest {

    @Test
    @DisplayName("Within active request scope, request-scoped handle and openConnection() share one owned session")
    void requestScopeAndDirectOpenConnectionReuseSameUnderlyingSession() {
        try (CommunityPersistenceEngine engine = new CommunityPersistenceEngine(testConfig())) {
            PersistenceSessionBox box =
                    new PersistenceSessionBox(engine, TransactionIsolation.READ_COMMITTED, true);

            ScopedValue.where(PersistenceSessionBox.REQUEST_SESSION, box).run(() -> {
                assertThat(PersistenceSessionBox.currentOrNull()).isSameAs(box);

                try (var handlerConnection = PersistenceSessionBox.currentOrNull().requestScopedConnection();
                     var directConnection = engine.openConnection()) {
                    assertThat(handlerConnection).isNotNull();
                    assertThat(directConnection).isNotNull();
                    assertThat(engine.stats().activeConnections()).isEqualTo(1);
                }

                // Both handles above are non-owning wrappers in request scope.
                assertThat(engine.stats().activeConnections()).isEqualTo(1);
            });

            box.release();
            assertThat(engine.stats().activeConnections()).isZero();
        }
    }

    @Test
    @DisplayName("openConnection(StorageContext) does not reuse request-scoped shared session")
    void storageContextOpenConnectionDoesNotReuseActiveRequestScopeSession() {
        try (CommunityPersistenceEngine engine = new CommunityPersistenceEngine(testConfig())) {
            PersistenceSessionBox box =
                    new PersistenceSessionBox(engine, TransactionIsolation.READ_COMMITTED, true);

            ScopedValue.where(PersistenceSessionBox.REQUEST_SESSION, box).run(() -> {
                try (var requestScoped = engine.openConnection();
                     var storageScoped = engine.openConnection(ImmutableStorageContext.shared("tenant-a"))) {
                    assertThat(requestScoped).isNotNull();
                    assertThat(storageScoped).isNotNull();
                    assertThat(engine.stats().activeConnections()).isEqualTo(2);
                }

                // Closing request-scoped wrapper is non-owning; underlying owned session remains active.
                assertThat(engine.stats().activeConnections()).isEqualTo(1);
            });

            box.release();
            assertThat(engine.stats().activeConnections()).isZero();
        }
    }

    @Test
    @DisplayName("Within active request scope, repeated openConnection(StorageContext) with same tenant reuses one owned session")
    void storageContextOpenConnectionReusesActiveRequestScopeWhenTenantMatches() {
        try (CommunityPersistenceEngine engine = new CommunityPersistenceEngine(testConfig())) {
            PersistenceSessionBox box =
                    new PersistenceSessionBox(engine, TransactionIsolation.READ_COMMITTED, true);
            ImmutableStorageContext context = ImmutableStorageContext.shared("tenant-a");

            ScopedValue.where(PersistenceSessionBox.REQUEST_SESSION, box).run(() -> {
                try (var first = engine.openConnection(context);
                     var second = engine.openConnection(context)) {
                    assertThat(first).isNotNull();
                    assertThat(second).isNotNull();
                    assertThat(engine.stats().activeConnections()).isEqualTo(1);
                }

                // Both request-scoped handles above are non-owning wrappers.
                assertThat(engine.stats().activeConnections()).isEqualTo(1);
            });

            box.release();
            assertThat(engine.stats().activeConnections()).isZero();
        }
    }

    private static PersistenceConfig testConfig() {
        return new PersistenceConfig(
                "jdbc:h2:mem:community_request_scope_" + System.nanoTime() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
                "sa",
                "",
                4,
                1,
                5_000L,
                60_000L,
                600_000L,
                false,
                false,
                false,
                0,
                Map.of()
        );
    }
}
