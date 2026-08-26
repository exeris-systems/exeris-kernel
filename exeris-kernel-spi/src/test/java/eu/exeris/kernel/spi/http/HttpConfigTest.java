/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.spi.http;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Validation contract for {@link HttpConfig} — the gate an operator's configuration hits first.
 *
 * <p>Written with ADR-071, which is also when this record acquired its first test: the two header
 * bounds had been validated, carried and read by nothing, so there was no behaviour to pin.
 */
@DisplayName("HttpConfig: limit validation (ADR-071)")
class HttpConfigTest {

    private static HttpConfig server(int maxHeaderCount, int maxHeaderSize, long idleTimeoutMillis) {
        return new HttpConfig(
                HttpMode.SERVER,
                HttpConfig.DEFAULT_BIND_HOST,
                HttpConfig.DEFAULT_PORT,
                HttpConfig.DEFAULT_MAX_CONNECTIONS,
                idleTimeoutMillis,
                maxHeaderCount,
                maxHeaderSize,
                HttpConfig.DEFAULT_MAX_REQUEST_BODY_BYTES,
                true,
                HttpVersion.HTTP_2);
    }

    @Nested
    @DisplayName("Protective bounds — zero is refused, because zero refuses everything")
    class ProtectiveBounds {

        @Test
        @DisplayName("a zero header count is refused, and the message names the key and the reason")
        void zeroHeaderCountIsRefused() {
            // Not "unlimited": the parser refuses the first header at a bound of 0, so a server
            // configured this way serves nothing but failures. Refused here, where the value is
            // named, rather than once per request, where it reads as a client fault.
            assertThatThrownBy(() -> server(0, HttpConfig.DEFAULT_MAX_HEADER_SIZE,
                    HttpConfig.DEFAULT_IDLE_TIMEOUT_MS))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("maxRequestHeaderCount")
                    .hasMessageContaining("must be > 0");
        }

        @Test
        @DisplayName("a zero header size is refused for the same reason")
        void zeroHeaderSizeIsRefused() {
            assertThatThrownBy(() -> server(HttpConfig.DEFAULT_MAX_HEADER_COUNT, 0,
                    HttpConfig.DEFAULT_IDLE_TIMEOUT_MS))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("maxRequestHeaderSize")
                    .hasMessageContaining("must be > 0");
        }

        @Test
        @DisplayName("negatives were already refused and stay refused")
        void negativeBoundsAreRefused() {
            assertThatThrownBy(() -> server(-1, HttpConfig.DEFAULT_MAX_HEADER_SIZE,
                    HttpConfig.DEFAULT_IDLE_TIMEOUT_MS))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> server(HttpConfig.DEFAULT_MAX_HEADER_COUNT, -1,
                    HttpConfig.DEFAULT_IDLE_TIMEOUT_MS))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("one is the smallest bound that means anything, and it is accepted")
        void oneIsAccepted() {
            assertThatCode(() -> server(1, 1, HttpConfig.DEFAULT_IDLE_TIMEOUT_MS))
                    .doesNotThrowAnyException();
        }
    }

    @Nested
    @DisplayName("Capacity and timeout keys — zero disables, and that contract is unchanged")
    class CapacityKeys {

        @Test
        @DisplayName("a zero idle timeout stays legal and still means no timeout")
        void zeroIdleTimeoutIsStillNoTimeout() {
            // The other half of ADR-071's split, pinned here so tightening the protective bounds
            // cannot be generalised into "zero is always invalid" by a later reader.
            HttpConfig config = server(HttpConfig.DEFAULT_MAX_HEADER_COUNT,
                    HttpConfig.DEFAULT_MAX_HEADER_SIZE, 0L);

            assertThat(config.idleTimeoutMillis())
                    .as("0 = no timeout, per the published TransportConfig/HttpConfig contract")
                    .isZero();
        }

        @Test
        @DisplayName("a negative idle timeout is refused")
        void negativeIdleTimeoutIsRefused() {
            assertThatThrownBy(() -> server(HttpConfig.DEFAULT_MAX_HEADER_COUNT,
                    HttpConfig.DEFAULT_MAX_HEADER_SIZE, -1L))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    @DisplayName("the shipped defaults satisfy their own validation")
    void defaultsAreValid() {
        assertThatCode(HttpConfig::defaultServer).doesNotThrowAnyException();
        assertThatCode(HttpConfig::defaultClient).doesNotThrowAnyException();
    }
}
