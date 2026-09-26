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
     * Builds a {@link CoreSslHandles} from pre-wired stub handles. The error-queue clear does
     * nothing, {@code SSL_get_verify_result} reports {@code X509_V_OK}, and every other
     * verification or trust-store handle throws {@link UnsupportedOperationException}.
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
     * Builds a {@link CoreSslHandles} from pre-wired stub handles, including the error queue; the
     * verification and trust-store groups are {@link #unsupportedPeerVerification()} and
     * {@link #unsupportedTrustStore()}.
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
        return build(ctxHandles, handshakeHandles, ioHandles, errorQueue,
                unsupportedPeerVerification(), unsupportedTrustStore());
    }

    /**
     * Builds a {@link CoreSslHandles} from every handle group.
     *
     * @param ctxHandles       stub {@link CoreSslHandles.CtxHandles}
     * @param handshakeHandles stub {@link CoreSslHandles.HandshakeHandles}
     * @param ioHandles        stub {@link CoreSslHandles.IoHandles}
     * @param errorQueue       stub {@link CoreSslHandles.ErrorQueueHandles}
     * @param peerVerification stub {@link CoreSslHandles.PeerVerificationHandles}
     * @param trustStore       stub {@link CoreSslHandles.TrustStoreHandles}
     * @return assembled {@link CoreSslHandles}
     */
    @SuppressWarnings("PMD.ExcessiveParameterList")
    public static CoreSslHandles build(
            CoreSslHandles.CtxHandles ctxHandles,
            CoreSslHandles.HandshakeHandles handshakeHandles,
            CoreSslHandles.IoHandles ioHandles,
            CoreSslHandles.ErrorQueueHandles errorQueue,
            CoreSslHandles.PeerVerificationHandles peerVerification,
            CoreSslHandles.TrustStoreHandles trustStore) {
        return new CoreSslHandles(ctxHandles, handshakeHandles, ioHandles, errorQueue,
                peerVerification, trustStore);
    }

    /**
     * Verification handles for an engine that never expects a peer: {@code SSL_get_verify_result}
     * reports {@code X509_V_OK} and every other handle throws.
     *
     * @return the stub group
     */
    public static CoreSslHandles.PeerVerificationHandles unsupportedPeerVerification() {
        return new CoreSslHandles.PeerVerificationHandles(
                unsupported("SSL_CTX_set1_cert_store", void.class, long.class, long.class),
                unsupported("SSL_get0_param", long.class, long.class),
                unsupported("X509_VERIFY_PARAM_set1_host", int.class, long.class, long.class, long.class),
                unsupported("X509_VERIFY_PARAM_set_hostflags", void.class, long.class, int.class),
                unsupported("X509_VERIFY_PARAM_set1_ip", int.class, long.class, long.class, long.class),
                unsupported("SSL_ctrl", long.class, long.class, int.class, long.class, long.class),
                MethodHandles.dropArguments(MethodHandles.constant(long.class, 0L), 0, long.class),
                unsupported("X509_verify_cert_error_string", long.class, long.class));
    }

    /**
     * Trust-store handles that all throw.
     *
     * @return the stub group
     */
    public static CoreSslHandles.TrustStoreHandles unsupportedTrustStore() {
        return new CoreSslHandles.TrustStoreHandles(
                unsupported("X509_STORE_new", long.class),
                unsupported("X509_STORE_free", void.class, long.class),
                unsupported("X509_STORE_set_default_paths", int.class, long.class),
                unsupported("X509_STORE_load_file", int.class, long.class, long.class),
                unsupported("X509_get_default_cert_file", long.class),
                unsupported("X509_get_default_cert_dir", long.class),
                unsupported("X509_get_default_cert_file_env", long.class),
                unsupported("X509_get_default_cert_dir_env", long.class));
    }

    private static MethodHandle unsupported(String symbol, Class<?> returnType, Class<?>... parameters) {
        MethodHandle thrower = MethodHandles.throwException(returnType, UnsupportedOperationException.class)
                .bindTo(new UnsupportedOperationException(symbol + " is not stubbed"));
        return MethodHandles.dropArguments(thrower, 0, parameters);
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
