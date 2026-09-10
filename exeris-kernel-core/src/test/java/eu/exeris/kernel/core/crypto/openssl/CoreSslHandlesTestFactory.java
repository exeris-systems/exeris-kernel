/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.core.crypto.openssl;

import java.lang.invoke.MethodHandle;

/**
 * Test-only factory for assembling {@link CoreSslHandles} from stub {@link MethodHandle}s.
 *
 * <p>Lives in the same package as {@link CoreSslHandles} to access its package-private
 * constructor. This avoids widening visibility of the constructor to {@code public},
 * which would violate the "no leaky abstraction" rule — the outer modules must always
 * obtain handles via {@link CoreOpenSslLoader#load(java.lang.foreign.Arena)} and
 * {@link CoreOpenSslRuntime#handles()}.
 *
 * @since 0.5.0
 */
public final class CoreSslHandlesTestFactory {

    private CoreSslHandlesTestFactory() {
        // factory
    }

    /**
     * Builds a {@link CoreSslHandles} from pre-wired stub handles.
     *
     * @param ctxHandles       stub {@link CoreSslHandles.CtxHandles}
     * @param handshakeHandles stub {@link CoreSslHandles.HandshakeHandles}
     * @param ioHandles        stub {@link CoreSslHandles.IoHandles}
     * @return assembled {@link CoreSslHandles}
     */
    public static CoreSslHandles build(
            CoreSslHandles.CtxHandles ctxHandles,
            CoreSslHandles.HandshakeHandles handshakeHandles,
            CoreSslHandles.IoHandles ioHandles) {
        return new CoreSslHandles(ctxHandles, handshakeHandles, ioHandles);
    }
}
