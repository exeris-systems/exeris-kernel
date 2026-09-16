/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.spi.persistence;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * L0 Contract: Persistence domain value types centered on {@link TransactionIsolation}
 * and {@link PersistenceConfig} validation.
 *
 * <h2>TransactionIsolation ordinal stability</h2>
 * <p>Community tier maps ordinals to JDBC {@code Connection.TRANSACTION_*} constants.
 * Enterprise tier uses them in a lookup table for the PG wire protocol
 * {@code BEGIN TRANSACTION ISOLATION LEVEL ...} command assembly.
 * Silent reordering sends serializable queries under read-committed.
 *
 * @since 0.5.0
 */
@DisplayName("L0: Persistence Value Types — TransactionIsolation ordinals + PersistenceConfig")
class PersistenceValueTypesTest {

    // =========================================================================
    // TransactionIsolation ordinal stability
    // =========================================================================

    @Nested
    @DisplayName("TransactionIsolation ordinal stability — JDBC/wire-protocol mapping regression shield")
    class TransactionIsolationOrdinals {

        @Test
        @DisplayName("READ_UNCOMMITTED ordinal == 0")
        void readUncommittedOrdinal() {
            assertThat(TransactionIsolation.READ_UNCOMMITTED.ordinal()).isZero();
        }

        @Test
        @DisplayName("READ_COMMITTED ordinal == 1")
        void readCommittedOrdinal() {
            assertThat(TransactionIsolation.READ_COMMITTED.ordinal()).isEqualTo(1);
        }

        @Test
        @DisplayName("REPEATABLE_READ ordinal == 2")
        void repeatableReadOrdinal() {
            assertThat(TransactionIsolation.REPEATABLE_READ.ordinal()).isEqualTo(2);
        }

        @Test
        @DisplayName("SERIALIZABLE ordinal == 3")
        void serializableOrdinal() {
            assertThat(TransactionIsolation.SERIALIZABLE.ordinal()).isEqualTo(3);
        }

        @Test
        @DisplayName("Total count == 4 — new level requires JDBC/wire-protocol table review")
        void totalCount() {
            assertThat(TransactionIsolation.values()).hasSize(4);
        }

        @Test
        @DisplayName("Isolation levels are strictly ascending by ordinal")
        void ordinalIsStrictlyAscending() {
            TransactionIsolation[] levels = TransactionIsolation.values();
            for (int i = 1; i < levels.length; i++) {
                assertThat(levels[i].ordinal())
                        .as("%s ordinal must exceed %s ordinal", levels[i], levels[i - 1])
                        .isGreaterThan(levels[i - 1].ordinal());
            }
        }

        @Test
        @DisplayName("valueOf() resolves all canonical names")
        void valueOfResolvesAll() {
            assertThat(TransactionIsolation.valueOf("READ_UNCOMMITTED"))
                    .isEqualTo(TransactionIsolation.READ_UNCOMMITTED);
            assertThat(TransactionIsolation.valueOf("READ_COMMITTED"))
                    .isEqualTo(TransactionIsolation.READ_COMMITTED);
            assertThat(TransactionIsolation.valueOf("REPEATABLE_READ"))
                    .isEqualTo(TransactionIsolation.REPEATABLE_READ);
            assertThat(TransactionIsolation.valueOf("SERIALIZABLE"))
                    .isEqualTo(TransactionIsolation.SERIALIZABLE);
        }
    }

    @Nested
    @DisplayName("PersistenceConfig warm-up properties — defaults and fail-fast validation")
    class PersistenceConfigWarmupProperties {

        @Test
        @DisplayName("Absent warm-up properties use defaults")
        void absentWarmupPropertiesUseDefaults() {
            PersistenceConfig config = newConfig(Map.of());

            assertThat(config.poolWarmupEnabled()).isTrue();
            assertThat(config.poolWarmupConnections()).isEqualTo(2);
        }

        @Test
        @DisplayName("Invalid explicit pool.warmup.enabled throws IllegalArgumentException")
        void invalidWarmupEnabledThrows() {
            Map<String, String> properties = Map.of(PersistenceConfig.POOL_WARMUP_ENABLED_KEY, "yes");

            assertThatThrownBy(() -> newConfig(properties))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining(PersistenceConfig.POOL_WARMUP_ENABLED_KEY);
        }

        @Test
        @DisplayName("Non-integer explicit pool.warmup.connections throws IllegalArgumentException")
        void nonIntegerWarmupConnectionsThrow() {
            Map<String, String> properties = Map.of(PersistenceConfig.POOL_WARMUP_CONNECTIONS_KEY, "abc");

            assertThatThrownBy(() -> newConfig(properties))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining(PersistenceConfig.POOL_WARMUP_CONNECTIONS_KEY);
        }

        @Test
        @DisplayName("Out-of-range explicit pool.warmup.connections throws IllegalArgumentException")
        void outOfRangeWarmupConnectionsThrow() {
            Map<String, String> properties = Map.of(PersistenceConfig.POOL_WARMUP_CONNECTIONS_KEY, "9");

            assertThatThrownBy(() -> newConfig(properties))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining(PersistenceConfig.POOL_WARMUP_CONNECTIONS_KEY);
        }

        private static PersistenceConfig newConfig(Map<String, String> properties) {
            return new PersistenceConfig(
                    "jdbc:postgresql://localhost:5432/exeris",
                    "exeris",
                    "secret",
                    16,
                    4,
                    30_000L,
                    600_000L,
                    1_800_000L,
                    true,
                    false,
                    false,
                    10,
                    properties);
        }
    }
}

