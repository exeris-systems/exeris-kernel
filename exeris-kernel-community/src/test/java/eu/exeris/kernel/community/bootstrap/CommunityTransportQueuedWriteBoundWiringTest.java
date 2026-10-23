/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.bootstrap;

import eu.exeris.kernel.community.transport.CommunityQueuedWriteBoundResolver;
import eu.exeris.kernel.community.transport.MapConfigProvider;
import eu.exeris.kernel.spi.transport.TransportConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Community: the outbound write bound an operator configures is the one the transport subsystem
 * carries, asserted on the record the carrier is built from rather than on the resolver alone.
 */
@DisplayName("Community: transport subsystem honours transport.maxQueuedWriteBytes")
class CommunityTransportQueuedWriteBoundWiringTest {

    @Test
    @DisplayName("the configured bound lands on the TransportConfig the carrier is built from")
    void configuredBoundReachesTransportConfig() {
        TransportConfig config = CommunityTransportSubsystem.buildTransportConfig(
                new MapConfigProvider(Map.of("transport.mode", "SERVER"),
                        Map.of(CommunityQueuedWriteBoundResolver.KEY, 4096)));

        assertThat(config.maxQueuedWriteBytes()).isEqualTo(4096);
    }

    @Test
    @DisplayName("with the key absent the record carries the default bound")
    void absentKeyKeepsDefault() {
        TransportConfig config = CommunityTransportSubsystem.buildTransportConfig(
                new MapConfigProvider(Map.of("transport.mode", "SERVER"), Map.of()));

        assertThat(config.maxQueuedWriteBytes()).isEqualTo(TransportConfig.DEFAULT_MAX_QUEUED_WRITE_BYTES);
    }
}
