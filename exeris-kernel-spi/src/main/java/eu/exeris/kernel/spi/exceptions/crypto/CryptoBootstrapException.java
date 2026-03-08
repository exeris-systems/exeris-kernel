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
 * Thrown by {@link eu.exeris.kernel.spi.crypto.KernelCryptoProvider#createTlsEngine} when the crypto engine
 * cannot be initialised (e.g., missing native cryptographic library, invalid certificate,
 * insufficient off-heap budget).
 *
 * <h2>Hierarchy &amp; java:S110</h2>
 * <p>Extends {@link ExerisKernelException} <em>directly</em> (skipping the
 * {@link TlsException} intermediate layer) to remain within the
 * {@code java:S110} inheritance-depth limit of&nbsp;5:
 * {@code Object → Throwable → Exception → RuntimeException →
 * ExerisKernelException → CryptoBootstrapException}.
 *
 * <h2>rawArgs Binary Layout</h2>
 * <pre>
 * index 0 → String providerName  (which provider failed to initialise)
 * index 1 → String reason        (failure cause — static constant, never formatted)
 * </pre>
 *
 * @since 0.5.0
 */
public final class CryptoBootstrapException extends ExerisKernelException {

    private static final String MESSAGE = "Crypto provider bootstrap failed";

    public CryptoBootstrapException(String providerName, String reason) {
        super(KernelErrorCodes.EX_NET_2002, MESSAGE, null, providerName, reason);
    }

    public CryptoBootstrapException(String providerName, String reason, Throwable cause) {
        super(KernelErrorCodes.EX_NET_2002, MESSAGE, cause, providerName, reason);
    }
}

