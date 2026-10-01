/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.transport;

import eu.exeris.kernel.community.memory.CommunityMemoryProvider;
import eu.exeris.kernel.spi.memory.MemoryAllocator;
import eu.exeris.kernel.spi.memory.MemoryProviderConfig;
import eu.exeris.kernel.spi.transport.TransportEngine;
import eu.exeris.kernel.spi.transport.TransportStream;
import eu.exeris.kernel.tck.contract.transport.TransportZeroAllocTck;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;

@DisplayName("Community: Transport Zero-Alloc TCK (MultiReactor=2)")
class CommunityTransportZeroAllocMultiReactor2TckTest extends TransportZeroAllocTck {

    private MemoryAllocator allocator;
    private CommunityTransportTestHarness.Pair pair;

    @AfterEach
    @SuppressWarnings("unused")
    void closeServerSideEngine() {
        if (pair != null) {
            pair.closeConnections();
            pair.serverEngine().close();
        }
    }

    @Override
    protected int maxExerisAllocationsPerIteration() {
        return 16;
    }

    @Override
    protected TransportEngine createEngine() {
        allocator = new CommunityMemoryProvider().createAllocator(MemoryProviderConfig.defaults());
        pair = CommunityTransportTestHarness.openLoopbackPair(allocator, true, 2);
        return pair.clientEngine();
    }

    @Override
    protected MemoryAllocator createAllocator() {
        return allocator;
    }

    @Override
    protected TransportStream createWritableStream() {
        return pair.clientStream();
    }
}
