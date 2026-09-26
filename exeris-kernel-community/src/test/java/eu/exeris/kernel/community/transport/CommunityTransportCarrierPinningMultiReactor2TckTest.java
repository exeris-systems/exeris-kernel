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

// MultiReactor variants of the carrier-pinning TCK drive multiple server reactor
// threads contending for a single virtual-thread carrier pool. On the constrained
// 2-vCPU GitHub Actions runner this is a true stress scenario — even with the
// Sprint 0a VT scheduler bump (parallelism=16/maxPoolSize=64) and the 30 s drain
// budget mirrored into the main `build-and-verify` job, the drain pipeline still
// times out under thread pressure when two or more reactors mount onto the same
// pool. The single-reactor `CommunityTransportCarrierPinningTckTest` carries the
// baseline contract invariant in the main job; the multi-reactor variants run
// only in the dedicated `transport-stress-gate` job (`@Tag("stress")` selector),
// where the same VT scheduler bump applies and the higher drain budget is
// expected. Once the v0.8 Sprint 7 non-blocking-ingress refactor lands these can
// drop the tag and rejoin the main matrix.
@Tag("stress")
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
