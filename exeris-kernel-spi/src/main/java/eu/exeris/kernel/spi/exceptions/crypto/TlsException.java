/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.spi.exceptions.crypto;

import eu.exeris.kernel.spi.exceptions.ExerisKernelException;
import eu.exeris.kernel.spi.exceptions.KernelErrorCodes;

/**
 * Base SPI exception for TLS/Crypto failures.
 *
 * <h2>Hierarchy</h2>
 * <p>Extends {@link ExerisKernelException} directly — one level below
 * {@code RuntimeException} in the Kernel tree — to stay within the
 * {@code java:S110} depth limit of 5 ({@code Object → Throwable → Exception
 * → RuntimeException → ExerisKernelException → TlsException}).
 * Concrete SPI subclasses such as {@link CryptoBootstrapException} inherit
 * directly from this class. Related kernel exceptions such as
 * {@code TlsHandshakeException} also extend {@link ExerisKernelException}
 * but do not derive from {@code TlsException}, and therefore are not
 * automatically handled via this base type.
 *
 * <h2>Zero-Allocation Contract</h2>
 * <p>No string formatting occurs in any constructor. Numeric context is stored in
 * {@link #rawArgs()} for binary Glass-Box serialization by the Enterprise telemetry tier.
 *
 * @since 0.5.0
 */
public class TlsException extends ExerisKernelException {

    private static final String MESSAGE = "TLS operation failed";

    public TlsException(String detail) {
        super(KernelErrorCodes.EX_NET_2001, MESSAGE, null, 0, detail);
    }

    public TlsException(String detail, Throwable cause) {
        super(KernelErrorCodes.EX_NET_2001, MESSAGE, cause, 0, detail);
    }

    public TlsException(int nativeErrorCode, String detail) {
        super(KernelErrorCodes.EX_NET_2001, MESSAGE, null, nativeErrorCode, detail);
    }
}

