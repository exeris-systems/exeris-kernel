/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.spi.exceptions.crypto;

import eu.exeris.kernel.spi.exceptions.ExerisKernelException;
import eu.exeris.kernel.spi.exceptions.KernelErrorCodes;

/**
 * General TLS/Crypto failure on the encrypt and session-management path, carrying
 * {@link KernelErrorCodes#EX_NET_2001}.
 *
 * <h2>Hierarchy</h2>
 * <p>Extends {@link ExerisKernelException} directly — one level below
 * {@code RuntimeException} in the Kernel tree — to stay within the
 * {@code java:S110} depth limit of 5 ({@code Object → Throwable → Exception
 * → RuntimeException → ExerisKernelException → TlsException}). The same depth limit
 * keeps the other crypto exceptions — {@link CryptoBootstrapException},
 * {@link TlsHandshakeException} and {@link TlsDecryptException} — off this type and
 * directly under {@link ExerisKernelException}, so catching {@code TlsException}
 * catches none of them.
 *
 * <h2>rawArgs Binary Layout</h2>
 * <pre>
 * index 0 → int    nativeErrorCode  (provider-specific error code; 0 when not applicable)
 * index 1 → String detail           (static message fragment, never formatted)
 * </pre>
 *
 * <p><b>Allocation:</b> allocates (one {@code rawArgs} array per instance); no constructor
 * formats a string, and the message text is a shared constant.
 *
 * @apiNote To catch every crypto failure, catch {@link ExerisKernelException} and branch on
 *          {@code errorCode()}; catching this type alone silences neither a bootstrap failure
 *          nor a decrypt failure.
 * @since 0.5
 */
public class TlsException extends ExerisKernelException {

    private static final String MESSAGE = "TLS operation failed";

    /**
     * Reports a TLS failure that no provider error code describes, such as a native call that
     * returned a {@code NULL} handle.
     *
     * @param detail static message fragment, never formatted at runtime; stored at
     *               {@code rawArgs[1]} while {@code rawArgs[0]} is {@code 0}
     */
    public TlsException(String detail) {
        super(KernelErrorCodes.EX_NET_2001, MESSAGE, null, 0, detail);
    }

    /**
     * Reports a TLS failure that wraps an underlying throwable, typically an FFM downcall that
     * threw out of {@code invokeExact}.
     *
     * @param detail static message fragment, never formatted at runtime; stored at
     *               {@code rawArgs[1]} while {@code rawArgs[0]} is {@code 0}
     * @param cause  the throwable that caused the failure
     */
    public TlsException(String detail, Throwable cause) {
        super(KernelErrorCodes.EX_NET_2001, MESSAGE, cause, 0, detail);
    }

    /**
     * Reports a TLS failure the provider has a numeric code for, keeping that code out of the
     * message and in the binary payload.
     *
     * @param nativeErrorCode provider-specific error code, stored at {@code rawArgs[0]};
     *                        interpretation belongs to the implementation tier
     * @param detail          static message fragment, never formatted at runtime; stored at
     *                        {@code rawArgs[1]}
     */
    public TlsException(int nativeErrorCode, String detail) {
        super(KernelErrorCodes.EX_NET_2001, MESSAGE, null, nativeErrorCode, detail);
    }
}

