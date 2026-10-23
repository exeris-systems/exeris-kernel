/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.transport;

import eu.exeris.kernel.community.memory.CommunityMemoryProvider;
import eu.exeris.kernel.spi.memory.MemoryAllocator;
import eu.exeris.kernel.spi.memory.MemoryProviderConfig;
import eu.exeris.kernel.spi.transport.TransportStream;
import eu.exeris.kernel.tck.contract.transport.AbstractTransportStreamTck;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;

@DisplayName("Community: NativeTcpStream TCK")
class CommunityNativeTcpStreamTckTest extends AbstractTransportStreamTck {

    // Small enough that a stream's writes reach the bound while the peer is held back.
    private static final int WRITE_BOUND_BYTES = 64 * 1024;

    private MemoryAllocator allocator;
    private CommunityTransportTestHarness.Pair pair;

    @AfterEach
    @SuppressWarnings("unused")
    void closeHarness() {
        if (pair != null) {
            pair.closeConnections();
            pair.closeEngines();
            pair = null;
        }
    }

    @Override
    protected StreamPair createStreamPair() {
        pair = CommunityTransportTestHarness.openLoopbackPair(allocator, false, 1, "127.0.0.1", 1024, WRITE_BOUND_BYTES);
        return new StreamPair(pair.clientStream(), pair.serverStream());
    }

    @Override
    protected boolean expectsTrueReset() {
        return true;   // NativeTcpStream.reset() == SO_LINGER-0 abortive close (RST)
    }

    @Override
    protected boolean supportsWriteBackpressure() {
        return true;
    }

    @Override
    protected AutoCloseable holdOutboundEgress(TransportStream writer) {
        return CommunityTransportTestHarness.holdOutboundConsumer(writer);
    }

    @Override
    protected MemoryAllocator createAllocator() {
        allocator = new CommunityMemoryProvider().createAllocator(MemoryProviderConfig.defaults());
        return allocator;
    }
}
