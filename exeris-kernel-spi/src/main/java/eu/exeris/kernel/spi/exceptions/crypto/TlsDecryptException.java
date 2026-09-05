/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.spi.exceptions.crypto;

import eu.exeris.kernel.spi.exceptions.ExerisKernelException;
import eu.exeris.kernel.spi.exceptions.KernelErrorCodes;

/**
 * Thrown when the TLS decryption (receive) path fails.
 *
 * <h2>One-Code-One-Schema Invariant</h2>
 * <p>Carries {@link KernelErrorCodes#EX_NET_2003} — intentionally separate from
 * {@link TlsHandshakeException} ({@code EX-NET-2001}, wrap/handshake path) so that
 * Glass-Box binary decoders can distinguish a send-side cipher failure from a
 * receive-side cipher failure without parsing the {@code detail} string.
 *
 * <h2>rawArgs Binary Layout</h2>
 * <pre>
 * index 0 → int    nativeErrorCode  (provider-specific SSL error code; -1 if not applicable)
 * index 1 → String detail           (static message fragment, never formatted)
 * </pre>
 *
 * <h2>Hierarchy &amp; java:S110</h2>
 * <p>Extends {@link ExerisKernelException} directly to remain within the
 * {@code java:S110} inheritance-depth limit of 5:
 * {@code Object → Throwable → Exception → RuntimeException →
 * ExerisKernelException → TlsDecryptException}.
 *
 * <p><b>Allocation:</b> allocates (one {@code rawArgs} array per instance) — except through the
 * no-argument {@linkplain #TlsDecryptException() sentinel constructor}, which reuses a static
 * array and writes no stack trace, and is the form the hot path uses.
 *
 * @since 0.5
 */
public final class TlsDecryptException extends ExerisKernelException {

    private static final String MESSAGE = "TLS decryption failed";

    /**
     * Pre-allocated rawArgs for the true zero-allocation Sentinel path.
     * Layout: index 0 = nativeErrorCode (-1 sentinel), index 1 = null (no detail).
     */
    private static final Object[] SENTINEL_ARGS = {-1, null};

    /**
     * Constructs a decrypt failure with a provider-specific error code and detail.
     *
     * @param nativeErrorCode provider-specific error code; -1 if not applicable
     * @param detail          static message fragment, never formatted at runtime
     */
    public TlsDecryptException(int nativeErrorCode, String detail) {
        super(KernelErrorCodes.EX_NET_2003, MESSAGE, null, nativeErrorCode, detail);
    }

    /**
     * Constructs a decrypt failure with a detail string and a chained cause.
     *
     * @param detail static message fragment, never formatted
     * @param cause  chained throwable (e.g. FFM {@code invokeExact} propagation)
     */
    public TlsDecryptException(String detail, Throwable cause) {
        super(KernelErrorCodes.EX_NET_2003, MESSAGE, cause, -1, detail);
    }

    /**
     * Constructs a decrypt failure with a detail string and no native error code.
     *
     * @param detail static message fragment, never formatted
     */
    public TlsDecryptException(String detail) {
        super(KernelErrorCodes.EX_NET_2003, MESSAGE, null, -1, detail);
    }

    /**
     * True zero-allocation Sentinel constructor for L0/L1 hot-path pre-allocation.
     *
     * <p>Hardcodes {@code enableSuppression=false} and {@code writableStackTrace=false}
     * to prevent callers from accidentally enabling stack-trace generation.
     * Uses a static pre-allocated {@link #SENTINEL_ARGS} array —
     * <strong>no heap allocation occurs</strong>.
     *
     * <p><strong>Usage:</strong> pre-allocate once as a static final field and rethrow:
     * {@snippet lang="java" :
     * private static final TlsDecryptException SENTINEL = new TlsDecryptException();
     * }
     */
    public TlsDecryptException() {
        super(KernelErrorCodes.EX_NET_2003, MESSAGE, null, false, false, SENTINEL_ARGS);
    }
}

