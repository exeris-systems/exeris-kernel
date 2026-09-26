/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.core.crypto.openssl;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;

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
     * Builds a {@link CoreSslHandles} from pre-wired stub handles, with an error-queue clear that
     * does nothing.
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
        return build(ctxHandles, handshakeHandles, ioHandles, noOpErrorQueue());
    }

    /**
     * Builds a {@link CoreSslHandles} from pre-wired stub handles, including the error queue.
     *
     * @param ctxHandles       stub {@link CoreSslHandles.CtxHandles}
     * @param handshakeHandles stub {@link CoreSslHandles.HandshakeHandles}
     * @param ioHandles        stub {@link CoreSslHandles.IoHandles}
     * @param errorQueue       stub {@link CoreSslHandles.ErrorQueueHandles}
     * @return assembled {@link CoreSslHandles}
     */
    public static CoreSslHandles build(
            CoreSslHandles.CtxHandles ctxHandles,
            CoreSslHandles.HandshakeHandles handshakeHandles,
            CoreSslHandles.IoHandles ioHandles,
            CoreSslHandles.ErrorQueueHandles errorQueue) {
        return new CoreSslHandles(ctxHandles, handshakeHandles, ioHandles, errorQueue);
    }

    /**
     * An error-queue handle whose clear does nothing.
     *
     * @return a stub {@link CoreSslHandles.ErrorQueueHandles}
     */
    public static CoreSslHandles.ErrorQueueHandles noOpErrorQueue() {
        return new CoreSslHandles.ErrorQueueHandles(MethodHandles.empty(
                java.lang.invoke.MethodType.methodType(void.class)));
    }
}
