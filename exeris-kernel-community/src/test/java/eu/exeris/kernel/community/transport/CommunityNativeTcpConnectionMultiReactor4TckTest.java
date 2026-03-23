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
import eu.exeris.kernel.tck.contract.transport.AbstractTransportConnectionTck;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;

@DisplayName("Community: NativeTcpConnection TCK (MultiReactor=4)")
class CommunityNativeTcpConnectionMultiReactor4TckTest extends AbstractTransportConnectionTck {

    private static final MemoryAllocator ALLOCATOR =
            new CommunityMemoryProvider().createAllocator(MemoryProviderConfig.defaults());

    private CommunityTransportTestHarness.Pair pair;

    @AfterAll
    @SuppressWarnings("unused")
    static void releaseAllocator() {
        ALLOCATOR.close();
    }

    @AfterEach
    @SuppressWarnings("unused")
    void closeHarness() {
        if (pair != null) {
            pair.closeEngines();
            pair = null;
        }
    }

    @Override
    protected ConnectionPair createConnectionPair() {
        pair = CommunityTransportTestHarness.openLoopbackPair(ALLOCATOR, false, 4);
        return new ConnectionPair(pair.serverConnection(), pair.clientConnection());
    }
}
