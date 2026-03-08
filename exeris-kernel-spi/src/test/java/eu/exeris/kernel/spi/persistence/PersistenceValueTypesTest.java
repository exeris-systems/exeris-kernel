/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.spi.persistence;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * L0 Contract: Persistence domain value types — {@link TransactionIsolation}
 * and {@link PersistenceEngineCapabilities}.
 *
 * <h2>TransactionIsolation ordinal stability</h2>
 * <p>Community tier maps ordinals to JDBC {@code Connection.TRANSACTION_*} constants.
 * Enterprise tier uses them in a lookup table for the PG wire protocol
 * {@code BEGIN TRANSACTION ISOLATION LEVEL ...} command assembly.
 * Silent reordering sends serializable queries under read-committed.
 *
 * <h2>PersistenceEngineCapabilities — Valhalla-readiness</h2>
 * <p>Constructor guards; DEFAULT/HIGH_PERFORMANCE templates pinned; {@code withProvider()} contract.
 *
 * @since 0.5.0
 */
@DisplayName("L0: Persistence Value Types — TransactionIsolation ordinals + PersistenceEngineCapabilities")
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

    // =========================================================================
    // PersistenceEngineCapabilities — templates + constructor guards + withProvider()
    // =========================================================================

    @Nested
    @DisplayName("PersistenceEngineCapabilities — DEFAULT/HIGH_PERFORMANCE templates + withProvider()")
    class PersistenceEngineCapabilitiesContract {

        @Test
        @DisplayName("DEFAULT: all capability flags false, transportName='BlockingTCP', providerId='default'")
        void defaultTemplate() {
            PersistenceEngineCapabilities d = PersistenceEngineCapabilities.DEFAULT;
            assertThat(d.supportsNativeProtocol()).isFalse();
            assertThat(d.supportsZeroCopyRows()).isFalse();
            assertThat(d.supportsKernelAsyncTransport()).isFalse();
            assertThat(d.supportsPerTenantPools()).isFalse();
            assertThat(d.transportName()).isEqualTo("BlockingTCP");
            assertThat(d.providerId()).isEqualTo("default");
        }

        @Test
        @DisplayName("HIGH_PERFORMANCE: all capability flags true, transportName='NativeAsync', providerId='high-performance'")
        void highPerformanceTemplate() {
            PersistenceEngineCapabilities hp = PersistenceEngineCapabilities.HIGH_PERFORMANCE;
            assertThat(hp.supportsNativeProtocol()).isTrue();
            assertThat(hp.supportsZeroCopyRows()).isTrue();
            assertThat(hp.supportsKernelAsyncTransport()).isTrue();
            assertThat(hp.supportsPerTenantPools()).isTrue();
            assertThat(hp.transportName()).isEqualTo("NativeAsync");
            assertThat(hp.providerId()).isEqualTo("high-performance");
        }

        @Test
        @DisplayName("Blank transportName throws IllegalArgumentException")
        void blankTransportNameThrows() {
            assertThatThrownBy(() ->
                    new PersistenceEngineCapabilities(false, false, false, false, "", "community"))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("Blank providerId throws IllegalArgumentException")
        void blankProviderIdThrows() {
            assertThatThrownBy(() ->
                    new PersistenceEngineCapabilities(false, false, false, false, "BlockingTCP", ""))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("withProvider() changes only providerId, preserves all other fields")
        void withProviderPreservesFields() {
            PersistenceEngineCapabilities branded =
                    PersistenceEngineCapabilities.DEFAULT.withProvider("postgres-community");
            assertThat(branded.providerId()).isEqualTo("postgres-community");
            assertThat(branded.transportName()).isEqualTo("BlockingTCP");
            assertThat(branded.supportsNativeProtocol()).isFalse();
            assertThat(branded.supportsZeroCopyRows()).isFalse();
        }

        @Test
        @DisplayName("withProvider() does not mutate the original DEFAULT constant")
        void withProviderDoesNotMutate() {
            PersistenceEngineCapabilities.DEFAULT.withProvider("x");
            assertThat(PersistenceEngineCapabilities.DEFAULT.providerId()).isEqualTo("default");
        }

        @Test
        @DisplayName("Structural equals: two identical custom caps are equal")
        void structuralEquality() {
            PersistenceEngineCapabilities a =
                    new PersistenceEngineCapabilities(true, true, false, false, "MyTransport", "acme");
            PersistenceEngineCapabilities b =
                    new PersistenceEngineCapabilities(true, true, false, false, "MyTransport", "acme");
            assertThat(a).isEqualTo(b);
        }

        @Test
        @DisplayName("Structural hashCode: equal caps have same hashCode")
        void structuralHashCode() {
            PersistenceEngineCapabilities a =
                    new PersistenceEngineCapabilities(true, false, true, false, "NativeAsync", "acme");
            PersistenceEngineCapabilities b =
                    new PersistenceEngineCapabilities(true, false, true, false, "NativeAsync", "acme");
            assertThat(a).hasSameHashCodeAs(b);
        }

        @Test
        @DisplayName("Different transportName yields inequality")
        void differentTransportNameNotEqual() {
            PersistenceEngineCapabilities a =
                    PersistenceEngineCapabilities.DEFAULT.withProvider("acme");
            PersistenceEngineCapabilities b =
                    new PersistenceEngineCapabilities(false, false, false, false, "NativeAsync", "acme");
            assertThat(a).isNotEqualTo(b);
        }
    }
}

