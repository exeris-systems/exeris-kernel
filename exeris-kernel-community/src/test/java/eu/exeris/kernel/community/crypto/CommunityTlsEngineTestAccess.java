/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.crypto;

import eu.exeris.kernel.core.crypto.openssl.ScriptedOpenSsl;
import eu.exeris.kernel.core.crypto.tls.OffHeapTlsEngine;
import eu.exeris.kernel.core.crypto.tls.TlsPeerIdentity;
import eu.exeris.kernel.spi.memory.MemoryAllocator;

/**
 * Builds a {@link CommunityTlsEngine} over a {@link ScriptedOpenSsl}, for tests of the transport
 * that consumes one. The engine is a client, expects {@code peer}, and is already bound.
 */
public final class CommunityTlsEngineTestAccess {

    private CommunityTlsEngineTestAccess() {
    }

    /**
     * A bound scripted client engine.
     *
     * @param openSsl   the script
     * @param allocator the session allocator; not owned by the engine
     * @param peer      the identity the engine expects
     * @return the engine, bound and ready for {@code beginHandshake}
     */
    public static CommunityTlsEngine scriptedClient(ScriptedOpenSsl openSsl, MemoryAllocator allocator,
                                                    TlsPeerIdentity peer) {
        OffHeapTlsEngine delegate = new OffHeapTlsEngine(openSsl.handles(), 0x1L, false, allocator);
        delegate.expectPeer(peer);
        CommunityTlsEngine engine = new CommunityTlsEngine(delegate, openSsl.sslSetFd(),
                openSsl.handles().ctx(), 0x1L, null, false);
        engine.bindFileDescriptor(3);
        return engine;
    }
}
