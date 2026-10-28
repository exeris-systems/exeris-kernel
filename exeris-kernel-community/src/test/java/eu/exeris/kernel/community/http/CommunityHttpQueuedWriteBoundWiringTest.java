/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.http;

import eu.exeris.kernel.community.transport.CommunityQueuedWriteBoundResolver;
import eu.exeris.kernel.community.transport.MapConfigProvider;
import eu.exeris.kernel.spi.http.HttpConfig;
import eu.exeris.kernel.spi.transport.TransportConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Community: the HTTP listener, server and client role alike, builds its own {@link TransportConfig},
 * so the outbound write bound has to reach it there and not only through the transport subsystem.
 */
@DisplayName("Community: HTTP listener honours transport.maxQueuedWriteBytes")
class CommunityHttpQueuedWriteBoundWiringTest {

    @Test
    @DisplayName("the configured bound lands on the listener's own TransportConfig")
    void configuredBoundReachesListenerConfig() {
        TransportConfig config = CommunityHttpTransportFactory.buildTransportConfig(
                HttpConfig.defaultServer(),
                8080,
                MapConfigProvider.ofInts(Map.of(CommunityQueuedWriteBoundResolver.KEY, 4096)),
                CommunityHttpTransportFactory.Role.SERVER);

        assertThat(config.maxQueuedWriteBytes()).isEqualTo(4096);
    }

    @Test
    @DisplayName("the configured bound lands on the client role's TransportConfig")
    void configuredBoundReachesClientConfig() {
        TransportConfig config = CommunityHttpTransportFactory.buildTransportConfig(
                HttpConfig.defaultServer(),
                0,
                MapConfigProvider.ofInts(Map.of(CommunityQueuedWriteBoundResolver.KEY, 8192)),
                CommunityHttpTransportFactory.Role.CLIENT);

        assertThat(config.maxQueuedWriteBytes()).isEqualTo(8192);
    }

    @Test
    @DisplayName("with no provider bound the listener carries the default bound")
    void noProviderKeepsDefault() {
        TransportConfig config = CommunityHttpTransportFactory.buildTransportConfig(
                HttpConfig.defaultServer(), 8080, null, CommunityHttpTransportFactory.Role.SERVER);

        assertThat(config.maxQueuedWriteBytes()).isEqualTo(TransportConfig.DEFAULT_MAX_QUEUED_WRITE_BYTES);
    }
}
