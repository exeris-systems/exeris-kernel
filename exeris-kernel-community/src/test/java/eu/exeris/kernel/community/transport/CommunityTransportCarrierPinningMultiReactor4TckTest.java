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

    private MemoryAllocator engineAllocator;
    private CommunityTransportTestHarness.Pair pair;

    /**
     * Drains and closes the client side first, through the contract, and only then the server:
     * the drain is a claim that queued writes reach a live peer, and the server's own stop waits
     * for its stream handlers, which end when their client stream does.
     */
    @Override
    protected void tearDownSubsystem() {
        try {
            super.tearDownSubsystem();
        } finally {
            if (pair != null) {
                pair.closeConnections();
                pair.serverEngine().close();
            }
            if (engineAllocator != null) {
                engineAllocator.close();
            }
        }
    }

    @Override
    protected TransportEngine createEngine() {
        engineAllocator = new CommunityMemoryProvider().createAllocator(MemoryProviderConfig.defaults());
        pair = CommunityTransportTestHarness.openLoopbackPair(
                engineAllocator, true, 4, warmupIterations() + hotPathIterations() + 1);
        return pair.clientEngine();
    }

    /**
     * The contract's buffers come from an allocator of their own, which the contract closes in its
     * teardown; the engines keep theirs until the server has stopped.
     */
    @Override
    protected MemoryAllocator createAllocator() {
        return new CommunityMemoryProvider().createAllocator(MemoryProviderConfig.defaults());
    }

    /**
     * Dials a new connection per call: the contract asks for one stream per virtual-thread slot,
     * and a {@link TransportStream} is owned by exactly one virtual thread. The server's ceiling is
     * sized in {@link #createEngine()} to admit every slot plus the pair's own connection.
     */
    @Override
    protected TransportStream createWritableStream() {
        return pair.connectClientStream();
    }
}
