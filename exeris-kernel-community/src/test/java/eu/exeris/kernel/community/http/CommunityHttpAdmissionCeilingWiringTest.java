/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.http;

import eu.exeris.kernel.community.transport.CommunityAdmissionCeilingResolver;
import eu.exeris.kernel.community.transport.MapConfigProvider;
import eu.exeris.kernel.spi.http.HttpConfig;
import eu.exeris.kernel.spi.transport.TransportConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Community: the HTTP listener honours the same PAQS ceiling as the transport subsystem.
 *
 * <p>The listener builds its own {@link TransportConfig}, so a key wired only in the subsystem
 * would apply to a standalone carrier and not to the server most deployments actually run — an
 * asymmetry indistinguishable, from the outside, from the key not working at all.
 *
 * @since 0.12.0
 */
@DisplayName("Community: HTTP listener honours transport.paqs.maxActiveStreams")
class CommunityHttpAdmissionCeilingWiringTest {

    @Test
    @DisplayName("the configured ceiling lands on the listener's own TransportConfig")
    void configuredCeilingReachesListenerConfig() {
        TransportConfig config = CommunityHttpTransportFactory.buildTransportConfig(
                HttpConfig.defaultServer(),
                8080,
                MapConfigProvider.ofInts(
                        Map.of(CommunityAdmissionCeilingResolver.KEY, 128)));

        assertThat(config.maxActiveStreams()).isEqualTo(128);
    }

    @Test
    @DisplayName("with no provider bound the listener carries the pre-key default")
    void noProviderKeepsDefault() {
        TransportConfig config = CommunityHttpTransportFactory.buildTransportConfig(
                HttpConfig.defaultServer(), 8080, null);

        assertThat(config.maxActiveStreams())
                .isEqualTo(TransportConfig.DEFAULT_MAX_ACTIVE_STREAMS);
    }
}
