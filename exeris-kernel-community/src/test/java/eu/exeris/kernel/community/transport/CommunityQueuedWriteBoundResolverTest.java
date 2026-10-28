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

@DisplayName("Community: CommunityQueuedWriteBoundResolver")
class CommunityQueuedWriteBoundResolverTest {

    @Test
    @DisplayName("no provider bound resolves to the default bound")
    void nullProviderResolvesToDefault() {
        assertThat(CommunityQueuedWriteBoundResolver.resolve(null))
                .isEqualTo(TransportConfig.DEFAULT_MAX_QUEUED_WRITE_BYTES);
    }

    @Test
    @DisplayName("an absent key resolves to the default bound")
    void absentKeyResolvesToDefault() {
        assertThat(CommunityQueuedWriteBoundResolver.resolve(MapConfigProvider.ofInts(Map.of())))
                .isEqualTo(TransportConfig.DEFAULT_MAX_QUEUED_WRITE_BYTES);
    }

    @Test
    @DisplayName("a set value is passed through unvalidated, the record being where it is refused")
    void setValueIsPassedThrough() {
        assertThat(CommunityQueuedWriteBoundResolver.resolve(
                MapConfigProvider.ofInts(Map.of(CommunityQueuedWriteBoundResolver.KEY, 12345))))
                .isEqualTo(12345);
        assertThat(CommunityQueuedWriteBoundResolver.resolve(
                MapConfigProvider.ofInts(Map.of(CommunityQueuedWriteBoundResolver.KEY, -1))))
                .isEqualTo(TransportConfig.UNBOUNDED_QUEUED_WRITE_BYTES);
        assertThat(CommunityQueuedWriteBoundResolver.resolve(
                MapConfigProvider.ofInts(Map.of(CommunityQueuedWriteBoundResolver.KEY, 0))))
                .isZero();
    }
}
