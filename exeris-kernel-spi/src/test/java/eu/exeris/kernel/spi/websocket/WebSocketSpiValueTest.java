/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.spi.websocket;

import eu.exeris.kernel.spi.exceptions.KernelErrorCodes;
import eu.exeris.kernel.spi.exceptions.http.WebSocketClosedException;
import eu.exeris.kernel.spi.http.HttpStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The value half of the duplex SPI, which is testable without a binding.
 *
 * <p>Written because {@code AbstractWebSocketExchangeTck} is abstract: until a binding exists it
 * runs nothing, and a surface that ships with no executable coverage is a surface nobody has
 * exercised. These are the parts that carry decisions rather than wire behaviour.
 */
@DisplayName("WebSocket SPI value types")
class WebSocketSpiValueTest {

    @Nested
    @DisplayName("WebSocketConfig")
    class ConfigValidation {

        private WebSocketConfig valid() {
            return WebSocketConfig.defaultServer("127.0.0.1", 0, List.of("https://studio.example"));
        }

        @Test
        @DisplayName("the defaults are internally consistent")
        void defaultsAreConsistent() {
            WebSocketConfig config = valid();
            assertThat(config.keepAliveIntervalMillis())
                    .as("a keepalive that fires after the idle timeout keeps nothing alive")
                    .isLessThan(config.idleTimeoutMillis());
            assertThat(config.maxMessageBytes())
                    .as("the default must clear the 8 KB that was measured to be too small")
                    .isGreaterThan(8 * 1024L);
        }

        @Test
        @DisplayName("a keepalive at or above the idle timeout is refused at construction")
        void keepAliveMustBeBelowIdleTimeout() {
            // The pair is only meaningful in one order. Refused here rather than discovered as a
            // connection that drops every minute for no visible reason.
            assertThatThrownBy(() -> new WebSocketConfig("127.0.0.1", 0, 8, 5_000L, 5_000L,
                    1024L, Set.of()))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("keepAliveIntervalMillis");
        }

        @Test
        @DisplayName("an empty origin allowlist is legal, and means no browser origin is accepted")
        void emptyAllowlistIsLegalAndClosed() {
            // Legal because a non-browser consumer has no Origin to list. The refusal it implies is
            // the engine's job; what this pins is that the config does not quietly treat empty as
            // "any", which is the reading that would turn a forgotten field into a hole.
            WebSocketConfig config = new WebSocketConfig("127.0.0.1", 0, 8, 30_000L, 10_000L,
                    1024L, Set.of());
            assertThat(config.allowedOrigins()).isEmpty();
        }

        @Test
        @DisplayName("the allowlist is copied, so a caller cannot mutate it after construction")
        void allowlistIsCopied() {
            Set<String> mutable = new java.util.HashSet<>(Set.of("https://studio.example"));
            WebSocketConfig config = new WebSocketConfig("127.0.0.1", 0, 8, 30_000L, 10_000L,
                    1024L, mutable);
            mutable.add("https://evil.example");
            assertThat(config.allowedOrigins())
                    .as("a security-bearing set that keeps the caller's reference is not a limit")
                    .containsExactly("https://studio.example");
        }

        @Test
        @DisplayName("out-of-range and non-positive values are refused")
        void rangesAreEnforced() {
            assertThatThrownBy(() -> new WebSocketConfig("127.0.0.1", 70_000, 8, 30_000L, 10_000L,
                    1024L, Set.of())).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> new WebSocketConfig("127.0.0.1", 0, 0, 30_000L, 10_000L,
                    1024L, Set.of())).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> new WebSocketConfig("127.0.0.1", 0, 8, 30_000L, 10_000L,
                    0L, Set.of())).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> new WebSocketConfig("  ", 0, 8, 30_000L, 10_000L,
                    1024L, Set.of())).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @DisplayName("WebSocketHandshake")
    class HandshakeDecision {

        @Test
        @DisplayName("accept carries no status and no subprotocol unless one is named")
        void acceptIsMinimal() {
            assertThat(WebSocketHandshake.accept().accepted()).isTrue();
            assertThat(WebSocketHandshake.accept().subprotocol()).isEmpty();
            assertThat(WebSocketHandshake.accept().refusalStatus()).isEmpty();
            assertThat(WebSocketHandshake.accept("exeris.lsp.v1").subprotocol())
                    .contains("exeris.lsp.v1");
        }

        @Test
        @DisplayName("a refusal carries the status the client receives")
        void refusalCarriesStatus() {
            WebSocketHandshake refused = WebSocketHandshake.refuse(HttpStatus.FORBIDDEN);
            assertThat(refused.accepted()).isFalse();
            assertThat(refused.refusalStatus()).contains(HttpStatus.FORBIDDEN);
        }

        @Test
        @DisplayName("a refusal cannot carry a 2xx, which would tell the client it was accepted")
        void refusalRejectsSuccessStatus() {
            assertThatThrownBy(() -> WebSocketHandshake.refuse(HttpStatus.OK))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("2xx");
        }

        @Test
        @DisplayName("a blank subprotocol is refused rather than silently negotiated as empty")
        void blankSubprotocolRejected() {
            assertThatThrownBy(() -> WebSocketHandshake.accept("  "))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @DisplayName("WebSocketCloseCode")
    class CloseCodes {

        @Test
        @DisplayName("1005 and 1006 are observable but never sendable, per RFC 6455")
        void reservedCodesAreNotSendable() {
            assertThat(WebSocketCloseCode.NO_STATUS_RECEIVED.sendable()).isFalse();
            assertThat(WebSocketCloseCode.ABNORMAL_CLOSURE.sendable()).isFalse();
            assertThat(WebSocketCloseCode.NORMAL_CLOSURE.sendable()).isTrue();
            assertThat(WebSocketCloseCode.MESSAGE_TOO_BIG.code()).isEqualTo(1009);
        }

        @Test
        @DisplayName("NO_STATUS_RECEIVED exists because that is the exit-without-shutdown case")
        void noStatusIsTheReportableCase() {
            // ADR-084 §8's driving requirement: an exit with no close frame must be reportable as a
            // protocol fault rather than indistinguishable from a clean goodbye.
            assertThat(WebSocketCloseCode.NO_STATUS_RECEIVED.code()).isEqualTo(1005);
        }
    }

    @Nested
    @DisplayName("WebSocketSession")
    class SessionValue {

        @Test
        @DisplayName("every component is required")
        void componentsRequired() {
            assertThatThrownBy(() -> new WebSocketSession(null, Optional.empty(), Optional.empty()))
                    .isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> new WebSocketSession(UUID.randomUUID(), null, Optional.empty()))
                    .isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> new WebSocketSession(UUID.randomUUID(), Optional.empty(), null))
                    .isInstanceOf(NullPointerException.class);
        }
    }

    @Nested
    @DisplayName("WebSocketClosedException")
    class ClosedException {

        @Test
        @DisplayName("carries the registered code and the documented rawArgs layout")
        void rawArgsLayout() {
            WebSocketClosedException thrown = WebSocketClosedException.notWritable(1_234L, 7L, 1006);
            assertThat(thrown.errorCode()).isEqualTo(KernelErrorCodes.EX_HTTP_4014);
            assertThat(thrown.rawArgs())
                    .as("index 0 connectionAgeMillis, index 1 messagesSent, index 2 closeCode")
                    .containsExactly(1_234L, 7L, 1006);
        }

        @Test
        @DisplayName("the message is static — no connection or payload content is formatted into it")
        void messageIsStatic() {
            WebSocketClosedException first = WebSocketClosedException.notWritable(1L, 1L, 1000);
            WebSocketClosedException second = WebSocketClosedException.notWritable(9_999L, 42L, 1006);
            assertThat(first.getMessage())
                    .as("a message that varies with the connection is a message carrying its data")
                    .isEqualTo(second.getMessage());
        }
    }
}
