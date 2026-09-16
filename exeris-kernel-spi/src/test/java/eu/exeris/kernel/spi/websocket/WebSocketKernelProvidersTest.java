/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.spi.websocket;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The accessors answer for both states, because both states are contract.
 *
 * <p>Unbound is not an error here and that is the part worth pinning: a consumer reads
 * {@code webSocketServerEngine()} to find out <em>whether</em> the subsystem is running, so an
 * accessor that threw would make "disabled" indistinguishable from "misconfigured".
 */
@DisplayName("WebSocketKernelProviders")
class WebSocketKernelProvidersTest {

    private static final WebSocketHandler HANDLER = exchange -> {
        // Never invoked; these cases assert binding visibility, not message flow.
    };

    @Nested
    @DisplayName("Unbound")
    class Unbound {

        @Test
        @DisplayName("every accessor answers empty rather than throwing")
        void accessorsAreEmpty() {
            assertThat(WebSocketKernelProviders.webSocketServerEngine()).isEmpty();
            assertThat(WebSocketKernelProviders.webSocketServerHandler()).isEmpty();
            assertThat(WebSocketKernelProviders.webSocketHandshakeHandler()).isEmpty();
        }
    }

    @Nested
    @DisplayName("Bound")
    class Bound {

        @Test
        @DisplayName("the handler accessor returns the instance that was bound")
        void handlerIsVisible() {
            ScopedValue.where(WebSocketKernelProviders.WEBSOCKET_SERVER_HANDLER, HANDLER)
                    .run(() -> assertThat(WebSocketKernelProviders.webSocketServerHandler())
                            .containsSame(HANDLER));
        }

        @Test
        @DisplayName("the handshake accessor returns the instance that was bound")
        void handshakeHandlerIsVisible() {
            WebSocketHandshakeHandler policy = request -> WebSocketHandshake.accept();

            ScopedValue.where(WebSocketKernelProviders.WEBSOCKET_HANDSHAKE_HANDLER, policy)
                    .run(() -> assertThat(WebSocketKernelProviders.webSocketHandshakeHandler())
                            .containsSame(policy));
        }

        @Test
        @DisplayName("binding one slot does not make the others appear bound")
        void slotsAreIndependent() {
            ScopedValue.where(WebSocketKernelProviders.WEBSOCKET_SERVER_HANDLER, HANDLER)
                    .run(() -> {
                        assertThat(WebSocketKernelProviders.webSocketServerHandler()).isPresent();
                        assertThat(WebSocketKernelProviders.webSocketServerEngine()).isEmpty();
                        assertThat(WebSocketKernelProviders.webSocketHandshakeHandler()).isEmpty();
                    });
        }
    }
}
