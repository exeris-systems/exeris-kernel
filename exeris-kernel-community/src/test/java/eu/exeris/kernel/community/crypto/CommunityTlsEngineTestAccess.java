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
 * that consumes one.
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
                openSsl.handles().ctx(), 0x1L, null, false, false);
        engine.bindFileDescriptor(3);
        return engine;
    }

    /**
     * An unbound scripted server engine, as a listener's crypto provider hands the carrier for each
     * accepted socket. Its {@code close()} frees its context through the script's
     * {@code SSL_CTX_free}.
     *
     * @param openSsl   the script
     * @param allocator the session allocator; not owned by the engine
     * @return the engine, not yet bound to a descriptor
     */
    public static CommunityTlsEngine scriptedServer(ScriptedOpenSsl openSsl, MemoryAllocator allocator) {
        OffHeapTlsEngine delegate = new OffHeapTlsEngine(openSsl.handles(), 0x1L, true, allocator);
        return new CommunityTlsEngine(delegate, openSsl.sslSetFd(),
                openSsl.handles().ctx(), 0x1L, null, false, false);
    }
}
