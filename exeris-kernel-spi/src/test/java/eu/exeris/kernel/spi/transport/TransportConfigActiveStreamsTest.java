/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.spi.transport;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * L0 Contract: {@code TransportConfig.maxActiveStreams} — the value ADR-071's ruling is applied to.
 *
 * <p>Covers the boundary an operator can reach by typing: refusal at construction rather than at
 * request time, the sentinel that removes the ceiling, and the bridge constructor that keeps a
 * caller written before the key on the behaviour it had.
 *
 * @since 0.12.0
 */
@DisplayName("L0: TransportConfig — stream admission ceiling")
class TransportConfigActiveStreamsTest {

    private static TransportConfig serverWith(int maxActiveStreams) {
        return new TransportConfig(
                TransportMode.SERVER, "127.0.0.1", 8443, 2, null, null, 1_000, 30_000,
                maxActiveStreams);
    }

    @Nested
    @DisplayName("Validation at construction")
    class Validation {

        @Test
        @DisplayName("a positive ceiling is carried as given")
        void positiveAccepted() {
            assertThat(serverWith(64).maxActiveStreams()).isEqualTo(64);
        }

        @Test
        @DisplayName("the unbounded sentinel is accepted")
        void unboundedAccepted() {
            assertThat(serverWith(TransportConfig.UNBOUNDED_ACTIVE_STREAMS).maxActiveStreams())
                    .isEqualTo(TransportConfig.UNBOUNDED_ACTIVE_STREAMS);
        }

        @Test
        @DisplayName("zero is refused — it is not a way to switch the ceiling off")
        void zeroRefused() {
            assertThatThrownBy(() -> serverWith(0))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("maxActiveStreams");
        }

        @Test
        @DisplayName("a negative that is not the sentinel is refused")
        void otherNegativeRefused() {
            assertThatThrownBy(() -> serverWith(-2))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("maxActiveStreams");
        }

        @Test
        @DisplayName("DISABLED skips the bound like every other field it skips")
        void disabledUnvalidated() {
            assertThat(TransportConfig.disabled().maxActiveStreams())
                    .isEqualTo(TransportConfig.UNBOUNDED_ACTIVE_STREAMS);
        }
    }

    @Nested
    @DisplayName("Bridge constructor")
    class Bridge {

        @Test
        @DisplayName("a caller that passes no ceiling gets the value the scheduler already enforced")
        void bridgeAppliesDefault() {
            TransportConfig config = new TransportConfig(
                    TransportMode.SERVER, "127.0.0.1", 8443, 2, null, null, 1_000, 30_000);

            assertThat(config.maxActiveStreams())
                    .isEqualTo(TransportConfig.DEFAULT_MAX_ACTIVE_STREAMS);
        }

        @Test
        @DisplayName("serverDefaults carries the default ceiling")
        void serverDefaultsCarriesDefault() {
            assertThat(TransportConfig.serverDefaults(9443).maxActiveStreams())
                    .isEqualTo(TransportConfig.DEFAULT_MAX_ACTIVE_STREAMS);
        }
    }

    @Nested
    @DisplayName("Diagnostics")
    class Diagnostics {

        @Test
        @DisplayName("toString reports the ceiling, and still redacts the TLS paths")
        void toStringCarriesCeiling() {
            String rendered = new TransportConfig(
                    TransportMode.SERVER, "127.0.0.1", 8443, 2, "/etc/tls/cert.pem",
                    "/etc/tls/key.pem", 1_000, 30_000, 64).toString();

            assertThat(rendered).contains("maxActiveStreams=64");
            assertThat(rendered).doesNotContain("/etc/tls");
        }
    }
}
