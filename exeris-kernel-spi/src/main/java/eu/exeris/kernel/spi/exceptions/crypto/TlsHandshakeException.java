/*
 * Copyright (C) 2025-2026 Exeris. All rights reserved.
 *
 * This code is part of the Exeris Systems.
 * Distributed under the proprietary Exeris Software License.
 * Unauthorized copying or distribution is prohibited.
 */
package eu.exeris.kernel.spi.exceptions.crypto;

import eu.exeris.kernel.spi.exceptions.ExerisKernelException;
import eu.exeris.kernel.spi.exceptions.KernelErrorCodes;

/**
 * Thrown when a TLS handshake cannot be initiated or completed.
 *
 * <h2>Hierarchy &amp; java:S110</h2>
 * <p>Extends {@link ExerisKernelException} <em>directly</em> (skipping the
 * {@link TlsException} intermediate layer) to remain within the
 * {@code java:S110} inheritance-depth limit of&nbsp;5:
 * {@code Object → Throwable → Exception → RuntimeException →
 * ExerisKernelException → TlsHandshakeException}.
 *
 * <h2>rawArgs Binary Layout</h2>
 * <pre>
 * index 0 → int    nativeErrorCode  (provider-specific error code; 0 if not applicable)
 * index 1 → String detail           (static message fragment, never formatted)
 * </pre>
 *
 * @since 0.5.0
 */
public final class TlsHandshakeException extends ExerisKernelException {

    private static final String MESSAGE = "TLS handshake failed";

    public TlsHandshakeException(int nativeErrorCode, String detail) {
        super(KernelErrorCodes.EX_NET_2001, MESSAGE, null, nativeErrorCode, detail);
    }

    public TlsHandshakeException(String detail, Throwable cause) {
        super(KernelErrorCodes.EX_NET_2001, MESSAGE, cause, -1, detail);
    }

    /**
     * Creates a state-machine / protocol-level exception with no native provider error code.
     * Uses {@code -1} as sentinel — outside the valid provider error code range.
     *
     * @param detail static message fragment, never formatted
     */
    public TlsHandshakeException(String detail) {
        super(KernelErrorCodes.EX_NET_2001, MESSAGE, null, -1, detail);
    }
}
