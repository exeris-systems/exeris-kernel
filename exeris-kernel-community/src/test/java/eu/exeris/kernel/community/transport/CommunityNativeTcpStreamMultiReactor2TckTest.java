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
import eu.exeris.kernel.tck.contract.transport.AbstractTransportStreamTck;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;

@DisplayName("Community: NativeTcpStream TCK (MultiReactor=2)")
class CommunityNativeTcpStreamMultiReactor2TckTest extends AbstractTransportStreamTck {

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
        pair = CommunityTransportTestHarness.openLoopbackPair(allocator, false, 2);
        return new StreamPair(pair.clientStream(), pair.serverStream());
    }

    @Override
    protected MemoryAllocator createAllocator() {
        allocator = new CommunityMemoryProvider().createAllocator(MemoryProviderConfig.defaults());
        return allocator;
    }
}
