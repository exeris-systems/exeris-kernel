/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.websocket;

import eu.exeris.kernel.community.transport.CommunityQueuedWriteBoundResolver;
import eu.exeris.kernel.community.transport.MapConfigProvider;
import eu.exeris.kernel.spi.transport.TransportConfig;
import eu.exeris.kernel.spi.websocket.WebSocketConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Community: the WebSocket listener builds its own {@link TransportConfig}, so the outbound write
 * bound has to reach it there and not only through the transport subsystem.
 */
@DisplayName("Community: WebSocket listener honours transport.maxQueuedWriteBytes")
class CommunityWebSocketQueuedWriteBoundWiringTest {

    private static final WebSocketConfig CONFIG = WebSocketConfig.defaultServer("127.0.0.1", 0, List.of());

    @Test
    @DisplayName("the configured bound lands on the listener's own TransportConfig")
    void configuredBoundReachesListenerConfig() {
        TransportConfig config = CommunityWebSocketTransportFactory.buildTransportConfig(
                CONFIG, 8080,
                MapConfigProvider.ofInts(Map.of(CommunityQueuedWriteBoundResolver.KEY, 4096)));

        assertThat(config.maxQueuedWriteBytes()).isEqualTo(4096);
    }

    @Test
    @DisplayName("with no provider bound the listener carries the default bound")
    void noProviderKeepsDefault() {
        TransportConfig config = CommunityWebSocketTransportFactory.buildTransportConfig(CONFIG, 8080, null);

        assertThat(config.maxQueuedWriteBytes()).isEqualTo(TransportConfig.DEFAULT_MAX_QUEUED_WRITE_BYTES);
    }
}
