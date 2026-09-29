/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.crypto;

import eu.exeris.kernel.core.crypto.tls.OffHeapTlsEngine;
import eu.exeris.kernel.core.crypto.tls.TlsPeerIdentity;

import java.util.function.BiConsumer;

/**
 * Builds a {@link CommunityKernelCryptoProvider} whose client-engine peer step is replaced, for tests
 * outside this package. The step runs inside a carrier's {@code connect}, after the dial and the
 * engine build and before the stream is registered, so a replacement can hold a connect there.
 */
public final class PeerBinderCryptoProviders {

    private PeerBinderCryptoProviders() {
    }

    /**
     * A provider whose client engines get their expected peer from {@code binder}.
     *
     * @param binder applies the expected peer to a freshly built engine
     * @return the provider
     */
    public static CommunityKernelCryptoProvider withPeerBinder(BiConsumer<OffHeapTlsEngine, TlsPeerIdentity> binder) {
        return new CommunityKernelCryptoProvider(binder::accept);
    }
}
