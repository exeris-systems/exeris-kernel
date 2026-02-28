/*
 * Copyright (C) 2025-2026 Exeris. All rights reserved.
 *
 * This code is part of the Exeris Systems.
 * Distributed under the proprietary Exeris Software License.
 * Unauthorized copying or distribution is prohibited.
 */
package eu.exeris.kernel.spi.transport;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * L0 Contract: Transport domain value types — {@link TransportMode},
 * {@link TransportStats}, and {@link TransportEngineCapabilities}.
 *
 * <h2>TransportMode ordinals</h2>
 * <p>Used in the resource-allocation switch table during bootstrap.
 * Silent reordering allocates server sockets in CLIENT-only deployments.
 *
 * <h2>TransportStats — Valhalla-readiness</h2>
 * <p>All-primitive record; EMPTY sentinel; structural equality.
 *
 * <h2>TransportEngineCapabilities — constructor guard + STANDARD/MULTIPLEXED templates</h2>
 * <p>Blank/null field guards; withProvider() contract.
 *
 * @since 0.5.0
 */
@DisplayName("L0: Transport Value Types — TransportMode ordinals + TransportStats + TransportEngineCapabilities")
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
        @DisplayName("equals(null) returns false")
        void equalsNullReturnsFalse() {
            assertThat(TransportStats.EMPTY).isNotEqualTo(null);
        }
    }

    // =========================================================================
    // TransportEngineCapabilities — constructor guard + template constants
    // =========================================================================

    @Nested
    @DisplayName("TransportEngineCapabilities — STANDARD/MULTIPLEXED + withProvider()")
    class TransportEngineCapabilitiesContract {

        @Test
        @DisplayName("STANDARD template: multiplexing=false, zeroCopy=false, name='StandardTCP', provider='community'")
        void standardTemplate() {
            TransportEngineCapabilities s = TransportEngineCapabilities.STANDARD;
            assertThat(s.supportsMultiplexing()).isFalse();
            assertThat(s.supportsZeroCopy()).isFalse();
            assertThat(s.transportName()).isEqualTo("StandardTCP");
            assertThat(s.providerId()).isEqualTo("community");
        }

        @Test
        @DisplayName("MULTIPLEXED template: multiplexing=true, zeroCopy=true, name='NativeMultiplexed', provider='enterprise'")
        void multiplexedTemplate() {
            TransportEngineCapabilities m = TransportEngineCapabilities.MULTIPLEXED;
            assertThat(m.supportsMultiplexing()).isTrue();
            assertThat(m.supportsZeroCopy()).isTrue();
            assertThat(m.transportName()).isEqualTo("NativeMultiplexed");
            assertThat(m.providerId()).isEqualTo("enterprise");
        }

        @Test
        @DisplayName("Blank transportName throws IllegalArgumentException")
        void blankTransportNameThrows() {
            assertThatThrownBy(() -> new TransportEngineCapabilities(false, false, "", "community"))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("Blank providerId throws IllegalArgumentException")
        void blankProviderIdThrows() {
            assertThatThrownBy(() -> new TransportEngineCapabilities(false, false, "TCP", ""))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("withProvider() changes only providerId, preserves all other fields")
        void withProviderPreservesFields() {
            TransportEngineCapabilities branded =
                    TransportEngineCapabilities.MULTIPLEXED.withProvider("my-quic");
            assertThat(branded.providerId()).isEqualTo("my-quic");
            assertThat(branded.supportsMultiplexing()).isTrue();
            assertThat(branded.supportsZeroCopy()).isTrue();
            assertThat(branded.transportName()).isEqualTo("NativeMultiplexed");
        }

        @Test
        @DisplayName("withProvider() does not mutate original constant")
        void withProviderDoesNotMutate() {
            TransportEngineCapabilities.STANDARD.withProvider("x");
            assertThat(TransportEngineCapabilities.STANDARD.providerId()).isEqualTo("community");
        }

        @Test
        @DisplayName("Structural equals: two identical custom caps are equal")
        void structuralEquality() {
            TransportEngineCapabilities a = new TransportEngineCapabilities(true, false, "MyTCP", "acme");
            TransportEngineCapabilities b = new TransportEngineCapabilities(true, false, "MyTCP", "acme");
            assertThat(a).isEqualTo(b);
        }

        @Test
        @DisplayName("Structural hashCode: equal caps have same hashCode")
        void structuralHashCode() {
            TransportEngineCapabilities a = new TransportEngineCapabilities(true, false, "MyTCP", "acme");
            TransportEngineCapabilities b = new TransportEngineCapabilities(true, false, "MyTCP", "acme");
            assertThat(a).hasSameHashCodeAs(b);
        }
    }
}

