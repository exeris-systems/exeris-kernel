/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.tck.contract.persistence;

import eu.exeris.kernel.spi.context.KernelProviders;
import eu.exeris.kernel.spi.exceptions.KernelErrorCodes;
import eu.exeris.kernel.spi.exceptions.persistence.PersistenceProviderException;
import eu.exeris.kernel.spi.persistence.BulkInserter;
import eu.exeris.kernel.spi.persistence.EngineStats;
import eu.exeris.kernel.spi.persistence.PersistenceConnection;
import eu.exeris.kernel.spi.persistence.PersistenceEngine;
import eu.exeris.kernel.spi.persistence.PersistenceHealthStatus;
import eu.exeris.kernel.spi.persistence.PersistenceStatement;
import eu.exeris.kernel.spi.persistence.QueryResult;
import eu.exeris.kernel.spi.persistence.TransactionIsolation;
import eu.exeris.kernel.spi.security.ImmutableStorageContext;
import eu.exeris.kernel.spi.security.StorageContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * TCK: Abstract base for {@link PersistenceEngine} contract verification.
 *
 * <h2>Contract</h2>
 * <ul>
 *   <li>{@code openConnection()} returns a valid, open connection</li>
 *   <li>{@code openConnection(StorageContext)} returns a tenant-scoped connection</li>
 *   <li>Connections correctly report {@link TransactionIsolation}</li>
 *   <li>{@code healthCheckDetailed()} returns a healthy status for a reachable database</li>
 *   <li>{@code stats()} returns valid metrics</li>
 *   <li>{@code close()} is idempotent</li>
 * </ul>
 *
 * @since 0.5.0
 */
public abstract class AbstractPersistenceEngineTck {

    /**
     * Creates a fully bootstrapped {@link PersistenceEngine}.
     */
    protected abstract PersistenceEngine createEngine();

    /**
     * Creates a {@link PersistenceEngine} configured with a dedicated datasource registered
     * under the key returned by {@link #dedicatedKey()}.
     *
     * <p>Returns {@code null} if the implementation under test does not support dedicated
     * datasource routing, causing all {@link DedicatedRoutingContract} tests to be skipped.
     *
     * @param dedicatedKey the datasource key to configure
     * @return engine with dedicated routing, or {@code null} to skip
     */
    protected PersistenceEngine createEngineWithDedicatedConfig(String dedicatedKey) {
        return null;
    }

    /**
     * Creates a {@link PersistenceEngine} with {@code rlsEnabled=true} and a dedicated
     * datasource registered under the key returned by {@link #dedicatedKey()}.
     * No interceptors should be registered on the returned engine.
     *
     * <p>Returns {@code null} if the implementation under test does not support this
     * combination, causing
     * {@link DedicatedRoutingContract#assert_dedicated_strategy_skips_rls_interceptor}
     * to be skipped.
     *
     * @param dedicatedKey the datasource key to configure
     * @return engine with rlsEnabled and dedicated routing, or {@code null} to skip
     */
    protected PersistenceEngine createEngineWithDedicatedRlsEnabledConfig(String dedicatedKey) {
        return null;
    }

    /**
     * Returns the datasource routing key used in {@link DedicatedRoutingContract} tests.
     * Default: {@code "ds-primary"}.
     */
    protected String dedicatedKey() {
        return "ds-primary";
    }

    private PersistenceEngine engine;

    @BeforeEach
    final void setUpEngine() {
        engine = createEngine();
    }

    @AfterEach
    final void tearDownEngine() {
        engine.close();
    }

    // =========================================================================
    // Connection contract
    // =========================================================================

    @Nested
    @DisplayName("Connection contract")
    class ConnectionContract {

        @Test
        @DisplayName("openConnection() returns an open connection")
        void openConnectionReturnsOpen() {
            try (PersistenceConnection conn = engine.openConnection()) {
                assertThat(conn).isNotNull();
                assertThat(conn.isOpen()).isTrue();
            }
        }

        @Test
        @DisplayName("openConnection() configures the connection for the ambient StorageContext")
        void openConnectionHonoursAmbientContext() {
            ImmutableStorageContext ambient = ImmutableStorageContext.shared("tck-ambient-tenant");
            List<StorageContext> configuredFor = new CopyOnWriteArrayList<>();
            engine.registerInterceptor((connection, storageContext) ->
                    configuredFor.add(storageContext));

            ScopedValue.where(KernelProviders.STORAGE_CONTEXT, ambient).run(() -> {
                try (PersistenceConnection conn = engine.openConnection()) {
                    assertThat(conn.isOpen()).isTrue();
                }
            });

            assertThat(configuredFor)
                    .as("the no-arg overload must resolve the ambient context and configure the "
                        + "connection for it exactly as the context overload would. Skipping that "
                        + "is not neutral: session-scoped settings survive pool checkin, so a "
                        + "connection handed over without isolation setup carries whatever the "
                        + "previous borrower published")
                    .containsExactly(ambient);
        }

        @Test
        @DisplayName("connection is closed after try-with-resources")
        void connectionClosedAfterTwr() {
            PersistenceConnection conn = engine.openConnection();
            conn.close();
            assertThat(conn.isOpen()).isFalse();
        }

        @Test
        @DisplayName("openConnection(StorageContext) returns tenant-scoped connection")
        void openConnectionWithStorageContext() {
            StorageContext ctx = ImmutableStorageContext.GLOBAL;
            try (PersistenceConnection conn = engine.openConnection(ctx)) {
                assertThat(conn).isNotNull();
                assertThat(conn.isOpen()).isTrue();
            }
        }
    }

    // =========================================================================
    // Transaction isolation
    // =========================================================================

    @Nested
    @DisplayName("Transaction isolation contract")
    class TransactionContract {

        @Test
        @DisplayName("beginTransaction() with READ_COMMITTED succeeds")
        void beginReadCommitted() {
            try (PersistenceConnection conn = engine.openConnection()) {
                assertThatCode(() -> conn.beginTransaction(TransactionIsolation.READ_COMMITTED, false))
                        .doesNotThrowAnyException();
                assertThat(conn.inTransaction()).isTrue();
                conn.rollback();
            }
        }

        @Test
        @DisplayName("beginTransaction() with SERIALIZABLE succeeds")
        void beginSerializable() {
            try (PersistenceConnection conn = engine.openConnection()) {
                assertThatCode(() -> conn.beginTransaction(TransactionIsolation.SERIALIZABLE, false))
                        .doesNotThrowAnyException();
                assertThat(conn.inTransaction()).isTrue();
                conn.rollback();
            }
        }

        @Test
        @DisplayName("beginTransaction() with readOnly flag succeeds")
        void beginReadOnly() {
            try (PersistenceConnection conn = engine.openConnection()) {
                assertThatCode(() -> conn.beginTransaction(TransactionIsolation.READ_COMMITTED, true))
                        .doesNotThrowAnyException();
                conn.rollback();
            }
        }

        @Test
        @DisplayName("commit() ends the transaction")
        void commitEndsTransaction() {
            try (PersistenceConnection conn = engine.openConnection()) {
                conn.beginTransaction();
                conn.commit();
                assertThat(conn.inTransaction()).isFalse();
            }
        }

        @Test
        @DisplayName("rollback() ends the transaction")
        void rollbackEndsTransaction() {
            try (PersistenceConnection conn = engine.openConnection()) {
                conn.beginTransaction();
                conn.rollback();
                assertThat(conn.inTransaction()).isFalse();
            }
        }
    }

    // =========================================================================
    // Health check & stats
    // =========================================================================

    @Nested
    @DisplayName("Health check & stats")
    class HealthAndStats {

        @Test
        @DisplayName("healthCheckDetailed() returns true for a reachable database")
        void healthCheckDetailedReturnsTrue() {
            assertThat(engine.healthCheckDetailed().healthy()).isTrue();
        }

        @Test
        @DisplayName("healthCheckDetailed() returns healthy=true for a reachable database")
        void healthCheckDetailedReturnsHealthy() {
            PersistenceHealthStatus status = engine.healthCheckDetailed();
            assertThat(status).isNotNull();
            assertThat(status.healthy()).isTrue();
            assertThat(status.message()).isNotBlank();
        }

        @Test
        @DisplayName("healthCheckDetailed() measures non-negative latency")
        void healthCheckDetailedMeasuresLatency() {
            PersistenceHealthStatus status = engine.healthCheckDetailed();
            assertThat(status.latencyNanos()).isGreaterThanOrEqualTo(0L);
        }

        @Test
        @DisplayName("healthCheckDetailed() after close() throws IllegalStateException")
        void healthCheckDetailedAfterCloseThrowsIllegalState() {
            engine.close();
            assertThatThrownBy(() -> engine.healthCheckDetailed())
                    .isInstanceOf(IllegalStateException.class);
        }

        @Test
        @DisplayName("stats() returns non-null snapshot")
        void statsNonNull() {
            EngineStats stats = engine.stats();
            assertThat(stats).isNotNull();
            assertThat(stats.maxConnections()).isGreaterThan(0);
        }
    }

    // =========================================================================
    // BulkInserter capability (tier-gated)
    // =========================================================================

    @Nested
    @DisplayName("BulkInserter capability contract")
    class BulkInserterCapability {

        @Test
        @DisplayName("openBulkInserter() returns Optional (empty or present based on tier)")
        void openBulkInserterReturnsOptional() {
            try (PersistenceConnection conn = engine.openConnection()) {
                conn.executeUpdate("CREATE TABLE IF NOT EXISTS tck_test (id INT)");

                try {
                    java.util.Optional<BulkInserter> inserter = conn.openBulkInserter("tck_test");
                    assertThat(inserter).isNotNull();
                    inserter.ifPresent(BulkInserter::close);
                } finally {
                    conn.executeUpdate("DROP TABLE IF EXISTS tck_test");
                }
            }
        }

        @Test
        @DisplayName("openBulkInserter() presence is stable for a given connection")
        void bulkInserterTierConsistency() {
            try (PersistenceConnection conn = engine.openConnection()) {
                conn.executeUpdate("CREATE TABLE IF NOT EXISTS tck_test (id INT)");
                try {
                    java.util.Optional<BulkInserter> first = conn.openBulkInserter("tck_test");
                    java.util.Optional<BulkInserter> second = conn.openBulkInserter("tck_test");

                    assertThat(first).isNotNull();
                    assertThat(second).isNotNull();
                    assertThat(first.isPresent())
                            .as("openBulkInserter() must return a stable present/empty result")
                            .isEqualTo(second.isPresent());

                    first.ifPresent(eu.exeris.kernel.spi.persistence.BulkInserter::close);
                    if (second.isPresent() && first.map(b -> b != second.get()).orElse(true)) {
                        second.get().close();
                    }
                } finally {
                    conn.executeUpdate("DROP TABLE IF EXISTS tck_test");
                }
            }
        }
    }

    // =========================================================================
    // Statement and result lifecycle
    // =========================================================================

    @Nested
    @DisplayName("Statement and query result lifecycle contract")
    class StatementLifecycleContract {

        @Test
        @DisplayName("closing QueryResult keeps the same connection reusable for another prepared query")
        void closingQueryResultKeepsConnectionReusable() {
            String table = "tck_stmt_lifecycle_" + System.nanoTime();

            try (PersistenceConnection conn = engine.openConnection()) {
                try {
                    conn.executeUpdate("CREATE TABLE " + table + " (id BIGINT PRIMARY KEY, v BIGINT)");
                    conn.executeUpdate("INSERT INTO " + table + " (id, v) VALUES (1, 11)");
                    conn.executeUpdate("INSERT INTO " + table + " (id, v) VALUES (2, 22)");

                    try (PersistenceStatement first = conn.prepare("SELECT v FROM " + table + " WHERE id = $1")) {
                        QueryResult firstResult = first.bindLong(0, 1L).executeQuery();
                        assertThat(firstResult.next()).isTrue();
                        assertThat(firstResult.row().getLong(0)).isEqualTo(11L);
                        firstResult.close();
                    }

                    try (PersistenceStatement second = conn.prepare("SELECT v FROM " + table + " WHERE id = $1");
                         QueryResult secondResult = second.bindLong(0, 2L).executeQuery()) {
                        assertThat(secondResult.next()).isTrue();
                        assertThat(secondResult.row().getLong(0)).isEqualTo(22L);
                    }
                } finally {
                    conn.executeUpdate("DROP TABLE IF EXISTS " + table);
                }
            }
        }

        @Test
        @DisplayName("try-with-resources over PersistenceStatement and QueryResult is safe and idempotent")
        void tryWithResourcesStatementAndResultIsSafeAndIdempotent() {
            assertThatCode(() -> {
                String table = "tck_stmt_lifecycle_" + System.nanoTime();

                try (PersistenceConnection conn = engine.openConnection()) {
                    try {
                        conn.executeUpdate("CREATE TABLE " + table + " (id BIGINT PRIMARY KEY, v BIGINT)");
                        conn.executeUpdate("INSERT INTO " + table + " (id, v) VALUES (1, 111)");

                        try (PersistenceStatement stmt = conn.prepare("SELECT v FROM " + table + " WHERE id = $1");
                             QueryResult result = stmt.bindLong(0, 1L).executeQuery()) {
                            assertThat(result.next()).isTrue();
                            assertThat(result.row().getLong(0)).isEqualTo(111L);
                            result.close();
                            stmt.close();
                        }
                    } finally {
                        conn.executeUpdate("DROP TABLE IF EXISTS " + table);
                    }
                }
            }).doesNotThrowAnyException();
        }
    }

    // =========================================================================
    // Lifecycle
    // =========================================================================

    @Nested
    @DisplayName("Lifecycle contract")
    class Lifecycle {

        @Test
        @DisplayName("close() is idempotent")
        void closeIsIdempotent() {
            engine.close();
            assertThatCode(() -> engine.close()).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("openConnection() after close() throws IllegalStateException")
        void openAfterCloseThrows() {
            engine.close();
            assertThatThrownBy(() -> engine.openConnection())
                    .isInstanceOf(IllegalStateException.class);
        }
    }

    // =========================================================================
    // DEDICATED routing contract
    // =========================================================================

    @Nested
    @DisplayName("DEDICATED routing contract")
    class DedicatedRoutingContract {

        @Test
        @DisplayName("DEDICATED strategy routes to configured dedicated datasource (connection opens successfully)")
        void assert_dedicated_strategy_routes_to_dedicated_datasource_config() {
            PersistenceEngine e = createEngineWithDedicatedConfig(dedicatedKey());
            assumeTrue(e != null, "Dedicated routing not configured for this provider \u2014 skipping");
            try (e) {
                StorageContext ctx = ImmutableStorageContext.dedicated("tenant-x", dedicatedKey());
                assertThatCode(() -> {
                    try (PersistenceConnection conn = e.openConnection(ctx)) {
                        assertThat(conn.isOpen()).isTrue();
                    }
                }).as("DEDICATED strategy with a configured key must open a connection without error")
                  .doesNotThrowAnyException();
            }
        }

        @Test
        @DisplayName("DEDICATED strategy with unknown key throws PersistenceProviderException EX-PERS-5006")
        void assert_dedicated_strategy_with_unknown_key_throws_pers_5006() {
            PersistenceEngine e = createEngineWithDedicatedConfig(dedicatedKey());
            assumeTrue(e != null, "Dedicated routing not configured for this provider \u2014 skipping");
            try (e) {
                StorageContext ctx = ImmutableStorageContext.dedicated(
                        "tenant-x", "ds-not-configured-" + System.nanoTime());
                assertThatThrownBy(() -> e.openConnection(ctx))
                        .isInstanceOfSatisfying(PersistenceProviderException.class, ex ->
                                assertThat(ex.errorCode())
                                        .as("Unknown DEDICATED key must throw EX-PERS-5006")
                                        .isEqualTo(KernelErrorCodes.EX_PERS_5006));
            }
        }

        @Test
        @DisplayName("DEDICATED strategy bypasses RLS interceptor requirement (rlsEnabled=true, no interceptor \u2192 no EX-PERS-5006)")
        void assert_dedicated_strategy_skips_rls_interceptor() {
            PersistenceEngine e = createEngineWithDedicatedRlsEnabledConfig(dedicatedKey());
            assumeTrue(e != null, "Dedicated + RLS engine not configured for this provider \u2014 skipping");
            try (e) {
                // No interceptors registered; rlsEnabled=true.
                // DEDICATED must succeed \u2014 it does not trigger the SHARED interceptor requirement check.
                StorageContext ctx = ImmutableStorageContext.dedicated("tenant-x", dedicatedKey());
                assertThatCode(() -> {
                    try (PersistenceConnection conn = e.openConnection(ctx)) {
                        assertThat(conn.isOpen()).isTrue();
                    }
                }).as("DEDICATED strategy must not require RLS interceptor even when rlsEnabled=true. "
                        + "If this fails, the engine is incorrectly applying the SHARED interceptor "
                        + "requirement check to DEDICATED connections.")
                  .doesNotThrowAnyException();
            }
        }
    }

    // =========================================================================
    // unwrap() seam contract (tier-blind — no driver type named here)
    // =========================================================================

    @Nested
    @DisplayName("unwrap(Class) seam contract")
    class UnwrapSeamContract {

        /** A type no PersistenceConnection should ever be assignable to. */
        private interface UnrelatedFacility {
        }

        @Test
        @DisplayName("unwrap(PersistenceConnection.class) returns a present, self-assignable instance")
        void unwrapToAssignableTypeIsPresent() {
            try (PersistenceConnection conn = engine.openConnection()) {
                java.util.Optional<PersistenceConnection> unwrapped =
                        conn.unwrap(PersistenceConnection.class);
                assertThat(unwrapped)
                        .as("default contract: unwrap to an assignable type yields a value")
                        .isPresent();
                assertThat(unwrapped.get()).isInstanceOf(PersistenceConnection.class);
            }
        }

        @Test
        @DisplayName("unwrap of an unrelated type returns empty (never null)")
        void unwrapToUnrelatedTypeIsEmpty() {
            try (PersistenceConnection conn = engine.openConnection()) {
                java.util.Optional<UnrelatedFacility> unwrapped = conn.unwrap(UnrelatedFacility.class);
                assertThat(unwrapped)
                        .as("default contract: unwrap to an unsupported type is empty, not null")
                        .isNotNull()
                        .isEmpty();
            }
        }

        @Test
        @DisplayName("unwrap() is side-effect-free — does not transfer ownership or close the connection")
        void unwrapDoesNotTransferOwnership() {
            try (PersistenceConnection conn = engine.openConnection()) {
                conn.unwrap(PersistenceConnection.class);
                conn.unwrap(UnrelatedFacility.class);
                assertThat(conn.isOpen())
                        .as("unwrap must not consume or close the connection")
                        .isTrue();
            }
        }
    }
}
