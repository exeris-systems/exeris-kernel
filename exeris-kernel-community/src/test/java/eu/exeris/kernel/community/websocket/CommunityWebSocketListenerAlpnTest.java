/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.websocket;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A WSS listener serves the WebSocket upgrade, an HTTP/1.1 exchange, so it must never offer {@code h2}.
 */
@DisplayName("CommunityWebSocketTransportFactory — the WSS listener offers http/1.1 only")
class CommunityWebSocketListenerAlpnTest {

    @Test
    @DisplayName("the listener's ALPN list names http/1.1 and no h2")
    void listenerOffersHttp11Only() {
        assertThat(CommunityWebSocketTransportFactory.LISTENER_ALPN).isEqualTo(List.of("http/1.1"));
    }
}
