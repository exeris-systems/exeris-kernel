/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.spi.crypto;

/**
 * SPI: TLS operation status codes returned by {@link TlsEngine} methods.
 *
 * <h2>The Wall (SPI Compliance)</h2>
 * <p>This enum is a pure contract — it has zero knowledge of OpenSSL error codes,
 * {@code SSL_ERROR_WANT_READ}, or any native constants. Implementations map native
 * error codes to these values internally.
 *
 * @since 0.5
 * @see TlsEngine
 */
public enum TlsStatus {

    /**
     * Operation completed successfully. Application data is available in the output buffer.
     *
     * <p>ARCH: "OK" is a universal TLS/protocol domain term (POSIX, IETF RFCs, JDK SSLEngineResult.Status).
     * Renaming to a longer alias would break the SPI contract and diverge from industry-standard naming.
     */
    @SuppressWarnings("PMD.ShortVariable") // "OK" is a canonical protocol domain term
    OK,

    /**
     * The engine needs to wrap (encrypt) data before it can proceed.
     * Caller must invoke {@link TlsEngine#wrap} with any pending outbound data.
     */
    NEED_WRAP,

    /**
     * The engine needs to unwrap (decrypt) more data before it can proceed.
     * Caller must read more bytes from the transport and invoke {@link TlsEngine#unwrap}.
     */
    NEED_UNWRAP,

    /**
     * The TLS handshake is still in progress. Continue calling {@code beginHandshake},
     * {@code wrap}, or {@code unwrap} until {@link #FINISHED} is returned.
     */
    NEED_HANDSHAKE,

    /**
     * The TLS handshake has completed successfully.
     * The engine is now ready for application-data exchange.
     */
    FINISHED,

    /**
     * The TLS session has been closed (received or sent {@code close_notify}).
     * The connection should be terminated.
     */
    CLOSED
}

