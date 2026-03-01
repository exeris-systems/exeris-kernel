/*
 * Copyright (C) 2025-2026 Exeris. All rights reserved.
 *
 * This code is part of the Exeris Systems.
 * Distributed under the proprietary Exeris Software License.
 * Unauthorized copying or distribution is prohibited.
 */
package eu.exeris.kernel.tck.contract.persistence;

import eu.exeris.kernel.spi.persistence.EngineStats;
import eu.exeris.kernel.spi.persistence.PersistenceConnection;
import eu.exeris.kernel.spi.persistence.PersistenceEngine;
import eu.exeris.kernel.spi.persistence.TransactionIsolation;
import eu.exeris.kernel.spi.security.ImmutableStorageContext;
import eu.exeris.kernel.spi.security.StorageContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * TCK: Abstract base for {@link PersistenceEngine} contract verification.
 *
 * <h2>Contract</h2>
 * <ul>
 *   <li>{@code openConnection()} returns a valid, open connection</li>
 *   <li>{@code openConnection(StorageContext)} returns a tenant-scoped connection</li>
 *   <li>Connections correctly report {@link TransactionIsolation}</li>
 *   <li>{@code healthCheck()} returns true for a healthy database</li>
 *   <li>{@code stats()} returns valid metrics</li>
 *   <li>{@code close()} is idempotent</li>
 * </ul>
 *
 * @since 0.5.0
 */
public abstract class AbstractPersistenceEngineTck {

    /** Creates a fully bootstrapped {@link PersistenceEngine}. */
    protected abstract PersistenceEngine createEngine();

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
        @DisplayName("healthCheck() returns true for a reachable database")
        void healthCheckReturnsTrue() {
            assertThat(engine.healthCheck()).isTrue();
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
}

