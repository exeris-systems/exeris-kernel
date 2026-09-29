/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.transport;

import eu.exeris.kernel.core.crypto.tls.TlsFailureDetail;
import eu.exeris.kernel.core.crypto.tls.TlsHandshakeFailureCodes;
import eu.exeris.kernel.spi.crypto.TlsEngine;
import eu.exeris.kernel.spi.exceptions.crypto.TlsHandshakeException;

/**
 * The codes of a TLS handshake a {@link NativeTcpStream} saw fail, kept so every caller that later
 * finds the stream closed is told why.
 *
 * <p>A record rather than an exception: the stream records the failure on whichever thread drove
 * the failed step — often a reactor — and each caller builds its own exception from it, so the stack
 * trace it carries is the caller's.
 *
 * @param sslError     the {@code SSL_get_error} code of the failed step, or {@code -1} if unknown
 * @param verifyResult the {@code X509_V_*} code of a peer that failed verification, {@code 0} for
 *                     none, or {@code -1} if there was nothing to verify
 */
record NativeTcpTlsFailure(int sslError, long verifyResult) {

    /**
     * Reads the codes {@code engine} left behind; {@code -1}/{@code -1} for an engine that keeps
     * none.
     *
     * @param engine the engine whose handshake returned {@code CLOSED}
     * @return the failure
     */
    /* default */ static NativeTcpTlsFailure from(TlsEngine engine) {
        if (engine instanceof TlsHandshakeFailureCodes codes) {
            return new NativeTcpTlsFailure(codes.handshakeFailureSslError(), codes.peerVerificationResult());
        }
        return new NativeTcpTlsFailure(-1, -1L);
    }

    /**
     * A new exception for the calling thread.
     *
     * @return {@code EX-NET-2001} with detail {@link TlsFailureDetail#PEER_VERIFICATION_FAILED} and
     *         the {@code X509_V_*} code when verification failed, otherwise detail
     *         {@link TlsFailureDetail#HANDSHAKE_FAILED} and the {@code SSL_get_error} code
     */
    /* default */ TlsHandshakeException toException() {
        if (verifyResult > 0) {
            return new TlsHandshakeException((int) verifyResult, TlsFailureDetail.PEER_VERIFICATION_FAILED);
        }
        return new TlsHandshakeException(sslError, TlsFailureDetail.HANDSHAKE_FAILED);
    }
}
