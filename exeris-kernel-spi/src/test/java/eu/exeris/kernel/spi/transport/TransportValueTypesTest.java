/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.spi.transport;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * L0 Contract: Transport domain value types — {@link TransportMode}
 * and {@link TransportStats}.
 *
 * <h2>TransportMode ordinals</h2>
 * <p>Used in the resource-allocation switch table during bootstrap.
 * Silent reordering allocates server sockets in CLIENT-only deployments.
 *
 * <h2>TransportStats — Valhalla-readiness</h2>
 * <p>All-primitive record; EMPTY sentinel; structural equality.
 *
 * @since 0.5.0
 */
@DisplayName("L0: Transport Value Types — TransportMode ordinals + TransportStats")
class TransportValueTypesTest {

    // =========================================================================
    // TransportMode ordinal stability
    // =========================================================================

    @Nested
    @DisplayName("TransportMode ordinal stability — bootstrap resource-allocation switch table")
    class TransportModeOrdinals {

        @Test
        @DisplayName("SERVER ordinal == 0")
        void serverOrdinal() {
            assertThat(TransportMode.SERVER.ordinal()).isZero();
        }

        @Test
        @DisplayName("CLIENT ordinal == 1")
        void clientOrdinal() {
            assertThat(TransportMode.CLIENT.ordinal()).isEqualTo(1);
        }

        @Test
        @DisplayName("DUAL ordinal == 2")
        void dualOrdinal() {
            assertThat(TransportMode.DUAL.ordinal()).isEqualTo(2);
        }

        @Test
        @DisplayName("DISABLED ordinal == 3")
        void disabledOrdinal() {
            assertThat(TransportMode.DISABLED.ordinal()).isEqualTo(3);
        }

        @Test
        @DisplayName("Total count == 4 — new mode requires resource-allocation table review")
        void totalCount() {
            assertThat(TransportMode.values()).hasSize(4);
        }

        @Test
        @DisplayName("valueOf() resolves all canonical names")
        void valueOfResolvesAll() {
            assertThat(TransportMode.valueOf("SERVER")).isEqualTo(TransportMode.SERVER);
            assertThat(TransportMode.valueOf("CLIENT")).isEqualTo(TransportMode.CLIENT);
            assertThat(TransportMode.valueOf("DUAL")).isEqualTo(TransportMode.DUAL);
            assertThat(TransportMode.valueOf("DISABLED")).isEqualTo(TransportMode.DISABLED);
        }
    }

    // =========================================================================
    // TransportStats — Valhalla-readiness
    // =========================================================================

    @Nested
    @DisplayName("TransportStats — EMPTY sentinel + structural equality")
    class TransportStatsContract {

        @Test
        @DisplayName("EMPTY has all fields at zero")
        void emptyAllZero() {
            TransportStats e = TransportStats.EMPTY;
            assertThat(e.activeConnections()).isZero();
            assertThat(e.activeStreams()).isZero();
            assertThat(e.totalAccepted()).isZero();
            assertThat(e.totalRejected()).isZero();
            assertThat(e.rttP50Micros()).isZero();
            assertThat(e.rttP95Micros()).isZero();
            assertThat(e.acceptFaults()).isZero();
        }

        @Test
        @DisplayName("Constructor stores all fields correctly")
        void constructorStoresFields() {
            TransportStats s = new TransportStats(5, 100L, 1000L, 3L, 500L, 2000L);
            assertThat(s.activeConnections()).isEqualTo(5);
            assertThat(s.activeStreams()).isEqualTo(100L);
            assertThat(s.totalAccepted()).isEqualTo(1000L);
            assertThat(s.totalRejected()).isEqualTo(3L);
            assertThat(s.rttP50Micros()).isEqualTo(500L);
            assertThat(s.rttP95Micros()).isEqualTo(2000L);
        }

        @Test
        @DisplayName("Structural equals: two identical stats are equal")
        void structuralEquality() {
            TransportStats a = new TransportStats(1, 10L, 100L, 0L, 200L, 800L);
            TransportStats b = new TransportStats(1, 10L, 100L, 0L, 200L, 800L);
            assertThat(a).isEqualTo(b);
        }

        @Test
        @DisplayName("Structural hashCode: equal stats have same hashCode")
        void structuralHashCode() {
            TransportStats a = new TransportStats(1, 10L, 100L, 0L, 200L, 800L);
            TransportStats b = new TransportStats(1, 10L, 100L, 0L, 200L, 800L);
            assertThat(a).hasSameHashCodeAs(b);
        }

        @Test
        @DisplayName("Different rttP95 yields inequality")
        void differentRttNotEqual() {
            TransportStats a = new TransportStats(1, 10L, 100L, 0L, 200L, 500L);
            TransportStats b = new TransportStats(1, 10L, 100L, 0L, 200L, 999L);
            assertThat(a).isNotEqualTo(b);
        }

        @Test
        @DisplayName("Different acceptFaults yields inequality")
        void differentAcceptFaultsNotEqual() {
            TransportStats a = new TransportStats(1, 10L, 100L, 0L, 200L, 800L, 0L);
            TransportStats b = new TransportStats(1, 10L, 100L, 0L, 200L, 800L, 7L);
            assertThat(a)
                    .as("two snapshots that differ only in how many connections broke during setup "
                            + "are not the same snapshot")
                    .isNotEqualTo(b);
        }

        @Test
        @DisplayName("The six-argument constructor reports no accept faults, and that is a claim")
        void sixArgumentConstructorReportsNoFaults() {
            TransportStats bridged = new TransportStats(5, 100L, 1000L, 3L, 500L, 2000L);

            assertThat(bridged.acceptFaults())
                    .as("the shape retained for drivers with no accept-setup path must say zero, "
                            + "not leave the component to whatever a future default becomes")
                    .isZero();
            assertThat(bridged)
                    .as("and it must agree with the canonical form spelled out in full — a bridge "
                            + "that lands values in different components is the failure it exists "
                            + "to prevent")
                    .isEqualTo(new TransportStats(5, 100L, 1000L, 3L, 500L, 2000L, 0L));
        }

        @Test
        @DisplayName("equals(null) returns false")
        void equalsNullReturnsFalse() {
            assertThat(TransportStats.EMPTY).isNotEqualTo(null);
        }
    }

}
