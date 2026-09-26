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
import eu.exeris.kernel.tck.contract.transport.TransportCarrierPinningTck;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;

// See CommunityTransportCarrierPinningMultiReactor2TckTest for the rationale —
// the 4-reactor variant is a strict superset of the contention scenario, so it
// inherits the same `@Tag("stress")` selector and runs only in the dedicated
// `transport-stress-gate` job until the Sprint 7 non-blocking-ingress refactor
// removes the FFM carrier pinning entirely.
@Tag("stress")
@DisplayName("Community: Transport Carrier Pinning TCK (MultiReactor=4)")
class CommunityTransportCarrierPinningMultiReactor4TckTest extends TransportCarrierPinningTck {

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
        pair = CommunityTransportTestHarness.openLoopbackPair(allocator, true, 4);
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
