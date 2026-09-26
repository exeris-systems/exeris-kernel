/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.bootstrap;

import eu.exeris.kernel.community.transport.CommunityAdmissionCeilingResolver;
import eu.exeris.kernel.community.transport.MapConfigProvider;
import eu.exeris.kernel.spi.transport.TransportConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Community: the PAQS ceiling an operator configures is the one the transport subsystem carries.
 *
 * <p>A resolver that reads the key proves nothing on its own — the failure this repository has
 * already shipped once (ADR-071) is a key that resolves correctly and reaches no consumer. This
 * asserts the value on the record the carrier is actually built from.
 *
 * @since 0.12.0
 */
@DisplayName("Community: transport subsystem honours transport.paqs.maxActiveStreams")
class CommunityTransportAdmissionCeilingWiringTest {

    @Test
    @DisplayName("the configured ceiling lands on the TransportConfig the carrier is built from")
    void configuredCeilingReachesTransportConfig() {
        TransportConfig config = CommunityTransportSubsystem.buildTransportConfig(
                new MapConfigProvider(Map.of("transport.mode", "SERVER"),
                        Map.of(CommunityAdmissionCeilingResolver.KEY, 128)));

        assertThat(config.maxActiveStreams()).isEqualTo(128);
    }

    @Test
    @DisplayName("with the key absent the record carries the pre-key default")
    void absentKeyKeepsDefault() {
        TransportConfig config = CommunityTransportSubsystem.buildTransportConfig(
                new MapConfigProvider(Map.of("transport.mode", "SERVER"), Map.of()));

        assertThat(config.maxActiveStreams())
                .isEqualTo(TransportConfig.DEFAULT_MAX_ACTIVE_STREAMS);
    }
}
