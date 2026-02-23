/*
 * Copyright (C) 2025-2026 Exeris. All rights reserved.
 *
 * This code is part of the Exeris Systems.
 * Distributed under the proprietary Exeris Software License.
 * Unauthorized copying or distribution is prohibited.
 */
package eu.exeris.kernel.spi.exceptions.crypto;


/**
 * Thrown when a TLS handshake cannot be initiated or completed.
 *
 * <h2>rawArgs Binary Layout</h2>
 * <pre>
 * index 0 → int    opensslErrorCode  (raw SSL_get_error() value; 0 if not applicable)
 * index 1 → String detail            (static message fragment, never formatted)
 * </pre>
 *
 * @since 0.5.0
 */
public final class TlsHandshakeException extends TlsException {

    public TlsHandshakeException(int opensslErrorCode, String detail) {
        super(opensslErrorCode, detail);
    }

    public TlsHandshakeException(String detail, Throwable cause) {
        super(detail, cause);
    }

    /**
     * Creates a state-machine / protocol-level exception with no native OpenSSL error code.
     * Uses {@code -1} as sentinel — outside the valid {@code SSL_get_error()} range (0–11).
     *
     * @param detail static message fragment, never formatted
     */
    public TlsHandshakeException(String detail) {
        super(-1, detail);
    }
}
