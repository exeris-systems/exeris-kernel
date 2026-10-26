/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.transport;

import eu.exeris.kernel.community.memory.CommunityMemoryProvider;
import eu.exeris.kernel.spi.memory.MemoryAllocator;
import eu.exeris.kernel.spi.memory.MemoryProviderConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The bound on the {@code TransportConfig} an engine is built from is the bound its streams enforce:
 * the accepted stream and the dialled one are both built by the carrier, and neither may fall back to
 * a default.
 */
@Timeout(value = 30, unit = TimeUnit.SECONDS)
@DisplayName("Community: the carrier hands the configured outbound bound to its streams")
class NativeTcpCarrierQueuedWriteBoundTest {

    private static final int CONFIGURED_BOUND = 12_345;

    @Test
    @DisplayName("the accepted and the dialled stream carry the engine's configured bound")
    void streamsCarryTheConfiguredBound() {
        MemoryAllocator allocator = new CommunityMemoryProvider().createAllocator(MemoryProviderConfig.defaults());
        CommunityTransportTestHarness.Pair pair = CommunityTransportTestHarness.openLoopbackPair(
                allocator, false, 1, "127.0.0.1", 1024, CONFIGURED_BOUND);
        try {
            assertThat(((NativeTcpStream) pair.serverStream()).maxQueuedWriteBytes())
                    .as("the accepted stream").isEqualTo(CONFIGURED_BOUND);
            assertThat(((NativeTcpStream) pair.clientStream()).maxQueuedWriteBytes())
                    .as("the dialled stream").isEqualTo(CONFIGURED_BOUND);
        } finally {
            pair.closeConnections();
            pair.closeEngines();
            allocator.close();
        }
    }
}
