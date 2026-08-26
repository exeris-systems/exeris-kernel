/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
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
 * index 0   → String providerName  (which provider failed to initialise)
 * index 1   → String reason        (failure cause — static constant, never formatted)
 * index 2..N → Object extraRawArgs (optional native context, e.g. result code, symbol name)
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

    /**
     * Constructor with additional raw context args (no cause).
     * Extra values are appended after {@code providerName} and {@code reason} in {@code rawArgs}.
     */
    public CryptoBootstrapException(String providerName, String reason, Object... extraRawArgs) {
        super(KernelErrorCodes.EX_NET_2002, MESSAGE, null, buildArgs(providerName, reason, extraRawArgs));
    }

    private static Object[] buildArgs(String providerName, String reason, Object... extra) {
        if (extra == null) {
            return new Object[]{providerName, reason};
        }
        Object[] args = new Object[2 + extra.length];
        args[0] = providerName;
        args[1] = reason;
        System.arraycopy(extra, 0, args, 2, extra.length);
        return args;
    }
}

