/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.community.transport;

import eu.exeris.kernel.community.memory.CommunityMemoryProvider;
import eu.exeris.kernel.spi.memory.MemoryAllocator;
import eu.exeris.kernel.spi.memory.MemoryProviderConfig;
import eu.exeris.kernel.spi.transport.TransportEngine;
import eu.exeris.kernel.spi.transport.TransportStream;
import eu.exeris.kernel.tck.contract.transport.TransportCarrierPinningTck;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;

@DisplayName("Community: Transport Carrier Pinning TCK (MultiReactor=2)")
class CommunityTransportCarrierPinningMultiReactor2TckTest extends TransportCarrierPinningTck {

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
