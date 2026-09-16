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
 * <p>Carries {@link KernelErrorCodes#EX_NET_2002} — the bootstrap-path code, distinct from the
 * record-path codes {@code EX-NET-2001} (encrypt/handshake) and {@code EX-NET-2003} (decrypt).
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
 * @since 0.5
 */
public final class CryptoBootstrapException extends ExerisKernelException {

    private static final String MESSAGE = "Crypto provider bootstrap failed";

    /**
     * Reports a bootstrap failure that has no underlying throwable — a rejected configuration or
     * a native symbol that is simply absent.
     *
     * @param providerName the failing provider's {@code providerName()}, stored at
     *                     {@code rawArgs[0]}
     * @param reason       why bootstrap failed, as a static constant fragment and never a
     *                     formatted string, stored at {@code rawArgs[1]}
     */
    public CryptoBootstrapException(String providerName, String reason) {
        super(KernelErrorCodes.EX_NET_2002, MESSAGE, null, providerName, reason);
    }

    /**
     * Reports a bootstrap failure that wraps an underlying throwable, typically an FFM downcall
     * that failed while loading or configuring the native library.
     *
     * @param providerName the failing provider's {@code providerName()}, stored at
     *                     {@code rawArgs[0]}
     * @param reason       why bootstrap failed, as a static constant fragment and never a
     *                     formatted string, stored at {@code rawArgs[1]}
     * @param cause        the throwable that caused the failure
     */
    public CryptoBootstrapException(String providerName, String reason, Throwable cause) {
        super(KernelErrorCodes.EX_NET_2002, MESSAGE, cause, providerName, reason);
    }

    /**
     * Reports a bootstrap failure carrying extra native context for the Glass-Box decoder, with
     * no underlying throwable.
     *
     * @param providerName  the failing provider's {@code providerName()}, stored at
     *                      {@code rawArgs[0]}
     * @param reason        why bootstrap failed, as a static constant fragment and never a
     *                      formatted string, stored at {@code rawArgs[1]}
     * @param extraRawArgs  additional context appended from {@code rawArgs[2]} onwards — a native
     *                      result code, a symbol name; {@code null} is treated as no extra args
     * @apiNote A single {@link Throwable} passed here binds to the cause-carrying constructor
     *          instead, so wrap it in an explicit array to record it as raw context.
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

