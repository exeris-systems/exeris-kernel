/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.transport;

import eu.exeris.kernel.spi.transport.TransportConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Community: {@link CommunityAdmissionCeilingResolver} — the PAQS ceiling reaches the carrier from
 * configuration, and falls back to the value the scheduler enforced before the key existed.
 *
 * @since 0.12.0
 */
@DisplayName("Community: PAQS admission ceiling resolution")
class CommunityAdmissionCeilingResolverTest {

    @Test
    @DisplayName("a configured ceiling is returned")
    void configuredCeilingWins() {
        assertThat(CommunityAdmissionCeilingResolver.resolve(
                MapConfigProvider.ofInts(Map.of(CommunityAdmissionCeilingResolver.KEY, 64))))
                .isEqualTo(64);
    }

    @Test
    @DisplayName("the unbounded sentinel is passed through, not clamped away")
    void sentinelPassedThrough() {
        assertThat(CommunityAdmissionCeilingResolver.resolve(MapConfigProvider.ofInts(
                Map.of(CommunityAdmissionCeilingResolver.KEY,
                        TransportConfig.UNBOUNDED_ACTIVE_STREAMS))))
                .isEqualTo(TransportConfig.UNBOUNDED_ACTIVE_STREAMS);
    }

    @Test
    @DisplayName("an absent key falls back to the pre-key default")
    void absentKeyFallsBack() {
        assertThat(CommunityAdmissionCeilingResolver.resolve(MapConfigProvider.ofInts(Map.of())))
                .isEqualTo(TransportConfig.DEFAULT_MAX_ACTIVE_STREAMS);
    }

    @Test
    @DisplayName("no bound provider falls back to the pre-key default")
    void noProviderFallsBack() {
        assertThat(CommunityAdmissionCeilingResolver.resolve(null))
                .isEqualTo(TransportConfig.DEFAULT_MAX_ACTIVE_STREAMS);
    }
}
