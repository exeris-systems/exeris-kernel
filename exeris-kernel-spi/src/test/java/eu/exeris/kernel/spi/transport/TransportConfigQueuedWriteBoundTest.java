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
 * L0 Contract: {@code TransportConfig.maxQueuedWriteBytes} — refusal at construction, the sentinel
 * that removes the bound, and the retained constructors that default it.
 */
@DisplayName("L0: TransportConfig — outbound write bound")
class TransportConfigQueuedWriteBoundTest {

    private static TransportConfig serverWith(int maxQueuedWriteBytes) {
        return new TransportConfig(
                TransportMode.SERVER, "127.0.0.1", 8443, 2, null, null, 1_000, 30_000,
                TransportConfig.DEFAULT_MAX_ACTIVE_STREAMS, maxQueuedWriteBytes);
    }

    @Nested
    @DisplayName("Validation at construction")
    class Validation {

        @Test
        @DisplayName("a positive bound is carried as given, down to one byte")
        void positiveAccepted() {
            assertThat(serverWith(1).maxQueuedWriteBytes()).isEqualTo(1);
            assertThat(serverWith(65_536).maxQueuedWriteBytes()).isEqualTo(65_536);
        }

        @Test
        @DisplayName("the unbounded sentinel is accepted")
        void unboundedAccepted() {
            assertThat(serverWith(TransportConfig.UNBOUNDED_QUEUED_WRITE_BYTES).maxQueuedWriteBytes())
                    .isEqualTo(-1);
        }

        @Test
        @DisplayName("zero is refused — a zero bound would block every write")
        void zeroRefused() {
            assertThatThrownBy(() -> serverWith(0))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("maxQueuedWriteBytes")
                    .hasMessageContaining("got: 0");
        }

        @Test
        @DisplayName("a negative that is not the sentinel is refused")
        void otherNegativeRefused() {
            assertThatThrownBy(() -> serverWith(-2))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("maxQueuedWriteBytes");
        }

        @Test
        @DisplayName("DISABLED does not validate the bound, like every other field it skips")
        void disabledSkipsValidation() {
            TransportConfig disabled = new TransportConfig(
                    TransportMode.DISABLED, "unbound", -1, -1, null, null, -1, -1, -1, 0);

            assertThat(disabled.maxQueuedWriteBytes()).isZero();
            assertThat(TransportConfig.disabled().maxQueuedWriteBytes())
                    .isEqualTo(TransportConfig.UNBOUNDED_QUEUED_WRITE_BYTES);
        }
    }

    @Nested
    @DisplayName("Retained constructors and defaults")
    class Retained {

        @Test
        @DisplayName("the nine-argument constructor applies the default bound")
        void nineArgumentAppliesDefault() {
            TransportConfig config = new TransportConfig(
                    TransportMode.SERVER, "127.0.0.1", 8443, 2, null, null, 1_000, 30_000, 77);

            assertThat(config.maxActiveStreams()).isEqualTo(77);
            assertThat(config.maxQueuedWriteBytes()).isEqualTo(TransportConfig.DEFAULT_MAX_QUEUED_WRITE_BYTES);
        }

        @Test
        @DisplayName("the eight-argument constructor applies both defaults")
        void eightArgumentAppliesBothDefaults() {
            TransportConfig config = new TransportConfig(
                    TransportMode.SERVER, "127.0.0.1", 8443, 2, null, null, 1_000, 30_000);

            assertThat(config.maxActiveStreams()).isEqualTo(TransportConfig.DEFAULT_MAX_ACTIVE_STREAMS);
            assertThat(config.maxQueuedWriteBytes()).isEqualTo(TransportConfig.DEFAULT_MAX_QUEUED_WRITE_BYTES);
        }

        @Test
        @DisplayName("serverDefaults carries the default bound and the default is 256 KiB")
        void serverDefaultsCarryTheDefault() {
            assertThat(TransportConfig.serverDefaults(8080).maxQueuedWriteBytes())
                    .isEqualTo(TransportConfig.DEFAULT_MAX_QUEUED_WRITE_BYTES)
                    .isEqualTo(256 * 1024);
        }

        @Test
        @DisplayName("toString names the bound")
        void toStringNamesTheBound() {
            assertThat(serverWith(4096).toString()).contains("maxQueuedWriteBytes=4096");
        }
    }
}
