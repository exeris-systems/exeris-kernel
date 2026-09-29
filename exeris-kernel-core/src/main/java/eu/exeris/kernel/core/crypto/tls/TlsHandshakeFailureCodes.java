/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.core.crypto.tls;

/**
 * The native codes behind a TLS handshake that ended in {@code CLOSED}, for a transport that has to
 * turn that status into an exception naming the cause.
 *
 * <p>{@code beginHandshake} reports a failed handshake as a status, not an exception, so the codes
 * are read from the engine after it returns. They are written by the thread that drove the failed
 * step, before that step returns.
 *
 * @since 0.12
 */
public interface TlsHandshakeFailureCodes {

    /**
     * The {@code SSL_get_error} code of the handshake step that failed.
     *
     * @return {@code 0} until a handshake step fails, then that step's {@code SSL_ERROR_*} code
     */
    int handshakeFailureSslError();

    /**
     * The result of verifying the server against the identity the engine expected.
     *
     * @return {@code -1} for an engine with no expected peer, and until a handshake with one has
     *         completed or failed; then an {@code X509_V_*} code, {@code 0} meaning verified
     */
    long peerVerificationResult();
}
