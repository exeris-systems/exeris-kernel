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

    private static HttpConfig client(String defaultAuthority) {
        return new HttpConfig(
                HttpMode.CLIENT,
                null,
                -1,
                HttpConfig.DEFAULT_MAX_CONNECTIONS,
                HttpConfig.DEFAULT_IDLE_TIMEOUT_MS,
                HttpConfig.DEFAULT_MAX_HEADER_COUNT,
                HttpConfig.DEFAULT_MAX_HEADER_SIZE,
                HttpConfig.DEFAULT_MAX_REQUEST_BODY_BYTES,
                false,
                HttpVersion.HTTP_1_1,
                defaultAuthority,
                HttpConfig.DEFAULT_MAX_HEADER_BLOCK_SIZE,
                HttpConfig.DEFAULT_MAX_HEADER_LIST_SIZE,
                HttpConfig.DEFAULT_MAX_STRING_LITERAL_SIZE);
    }

    private static HttpConfig withHeaderBlock(int maxHeaderBlockSize) {
        return withHttp2Bounds(maxHeaderBlockSize,
                HttpConfig.DEFAULT_MAX_HEADER_LIST_SIZE, HttpConfig.DEFAULT_MAX_STRING_LITERAL_SIZE);
    }

    private static HttpConfig withHeaderList(int maxHeaderListSize) {
        return withHttp2Bounds(HttpConfig.DEFAULT_MAX_HEADER_BLOCK_SIZE,
                maxHeaderListSize, HttpConfig.DEFAULT_MAX_STRING_LITERAL_SIZE);
    }

    private static HttpConfig withStringLiteral(int maxStringLiteralSize) {
        return withHttp2Bounds(HttpConfig.DEFAULT_MAX_HEADER_BLOCK_SIZE,
                HttpConfig.DEFAULT_MAX_HEADER_LIST_SIZE, maxStringLiteralSize);
    }

    private static HttpConfig withHttp2Bounds(int maxHeaderBlockSize,
                                              int maxHeaderListSize,
                                              int maxStringLiteralSize) {
        return new HttpConfig(
                HttpMode.SERVER,
                HttpConfig.DEFAULT_BIND_HOST,
                HttpConfig.DEFAULT_PORT,
                HttpConfig.DEFAULT_MAX_CONNECTIONS,
                HttpConfig.DEFAULT_IDLE_TIMEOUT_MS,
                HttpConfig.DEFAULT_MAX_HEADER_COUNT,
                HttpConfig.DEFAULT_MAX_HEADER_SIZE,
                HttpConfig.DEFAULT_MAX_REQUEST_BODY_BYTES,
                true,
                HttpVersion.HTTP_2,
                null,
                maxHeaderBlockSize,
                maxHeaderListSize,
                maxStringLiteralSize);
    }

    @Nested
    @DisplayName("HTTP/2 header block — protective, so zero is refused (ADR-071 tail)")
    class HeaderBlockBound {

        @Test
        @DisplayName("the pre-0.12 value is still the default, so the key changes reach and not behaviour")
        void defaultIsUnchanged() {
            assertThat(HttpConfig.defaultServer().maxHeaderBlockSize())
                    .isEqualTo(HttpConfig.DEFAULT_MAX_HEADER_BLOCK_SIZE)
                    .isEqualTo(65_536);
        }

        @Test
        @DisplayName("zero is refused — a protective bound has no unlimited reading")
        void zeroIsRefused() {
            assertThatThrownBy(() -> withHeaderBlock(0))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("http.maxHeaderBlockSize");
        }

        @Test
        @DisplayName("a negative bound is refused")
        void negativeIsRefused() {
            assertThatThrownBy(() -> withHeaderBlock(-1)).isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("raising it is accepted — which is the point an operator could not reach before")
        void raisingIsAccepted() {
            assertThatCode(() -> withHeaderBlock(1_048_576)).doesNotThrowAnyException();
        }
    }

    @Nested
    @DisplayName("HTTP/2 decoded field section — the bound the peer is actually told about")
    class HeaderListBound {

        @Test
        @DisplayName("the pre-0.12 value is still the default, so the key changes reach and not behaviour")
        void defaultIsUnchanged() {
            assertThat(HttpConfig.defaultServer().maxHeaderListSize())
                    .isEqualTo(HttpConfig.DEFAULT_MAX_HEADER_LIST_SIZE)
                    .isEqualTo(65_536);
        }

        @Test
        @DisplayName("it is a separate knob from the header-block bound, not an alias for it")
        void independentOfTheBlockBound() {
            // Sharing a default is not sharing a value. They bound compressed wire bytes and the
            // decoded field section respectively, so compression alone makes them independent —
            // and a config that could not separate them would have no way to express that.
            HttpConfig config = withHttp2Bounds(32_768, 200_000, 16_384);
            assertThat(config.maxHeaderListSize()).isEqualTo(200_000);
            assertThat(config.maxHeaderBlockSize()).isEqualTo(32_768);
        }

        @Test
        @DisplayName("zero is refused — a protective bound has no unlimited reading")
        void zeroIsRefused() {
            assertThatThrownBy(() -> withHeaderList(0))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("http.maxHeaderListSize");
        }

        @Test
        @DisplayName("a negative bound is refused")
        void negativeIsRefused() {
            assertThatThrownBy(() -> withHeaderList(-1)).isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("raising it is accepted — the direction an operator actually touches it")
        void raisingIsAccepted() {
            assertThatCode(() -> withHeaderList(1_048_576)).doesNotThrowAnyException();
        }
    }

    @Nested
    @DisplayName("HPACK string literal — the other constant ADR-071 named as its tail")
    class StringLiteralBound {

        @Test
        @DisplayName("the pre-0.12 value is still the default, so the key changes reach and not behaviour")
        void defaultIsUnchanged() {
            assertThat(HttpConfig.defaultServer().maxStringLiteralSize())
                    .isEqualTo(HttpConfig.DEFAULT_MAX_STRING_LITERAL_SIZE)
                    .isEqualTo(65_536);
        }

        @Test
        @DisplayName("zero is refused — a protective bound has no unlimited reading")
        void zeroIsRefused() {
            assertThatThrownBy(() -> withStringLiteral(0))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("http.maxStringLiteralSize");
        }

        @Test
        @DisplayName("a negative bound is refused")
        void negativeIsRefused() {
            assertThatThrownBy(() -> withStringLiteral(-1)).isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("lowering it is accepted — a constrained node's reason for touching this one")
        void loweringIsAccepted() {
            assertThatCode(() -> withStringLiteral(4_096)).doesNotThrowAnyException();
        }
    }

    @Nested
    @DisplayName("Client peer (ADR-074) — refused at construction, where the message names the key")
    class ClientPeer {

        // These assert CONSTRUCTION-time refusal specifically, and that is the whole point of the
        // slice. The engine refuses the same shapes at send() with an IllegalStateException, which
        // is a different code path throwing a different type — so a TCK case there does not cover
        // this one. Deferring a misconfigured key to the first request reports an operator mistake
        // as a client problem, which is the anti-pattern validateRequestLimits above argues against.

        @Test
        @DisplayName("no default peer is legal — an unaddressed request is then refused at send")
        void nullIsLegal() {
            assertThatCode(() -> client(null)).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("a host:port peer is accepted, bracketed IPv6 included")
        void wellFormedPeersAreAccepted() {
            assertThatCode(() -> client("payments.internal:8443")).doesNotThrowAnyException();
            assertThatCode(() -> client("[::1]:8080")).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("a blank peer is refused rather than treated as absent")
        void blankIsRefused() {
            assertThatThrownBy(() -> client("   "))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("omit the key instead");
        }

        @Test
        @DisplayName("a URL is refused, because it is what an operator writes when the key looks like one")
        void urlShapedValueIsRefused() {
            assertThatThrownBy(() -> client("https://payments.internal/api"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("not a URL");
        }

        @Test
        @DisplayName("a peer without a port is refused at construction, not at the first request")
        void missingPortIsRefused() {
            // HttpRequest carries no scheme, so there is no basis for choosing 80 over 443 — and
            // defaulting to the listener port is exactly what ADR-074 removed.
            assertThatThrownBy(() -> client("payments.internal"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("explicit port");
        }

        @Test
        @DisplayName("an unbracketed IPv6 literal is refused, because it is ambiguous rather than unusual")
        void unbracketedIpv6IsRefused() {
            // "::1:8080" is itself a valid IPv6 address, so reading it as host "::1" port 8080 is a
            // guess. Measured: both forms parse and resolve, which is what makes it silent.
            assertThatThrownBy(() -> client("::1:8080"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("bracketed");
        }
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
