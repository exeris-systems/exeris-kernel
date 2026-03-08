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
 * index 0 → int    nativeErrorCode  (provider-specific error code; -1 if not applicable)
 * index 1 → String detail           (static message fragment, never formatted)
 * </pre>
 *
 * @since 0.5.0
 */
public final class TlsHandshakeException extends ExerisKernelException {

    private static final String MESSAGE = "TLS handshake failed";

    /**
     * Pre-allocated rawArgs for the true zero-allocation Sentinel path.
     * Layout: index 0 = nativeErrorCode (-1 sentinel), index 1 = null (no detail).
     * This array is a static constant — never re-allocated on the hot path.
     */
    private static final Object[] SENTINEL_ARGS = {-1, null};

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

    /**
     * True zero-allocation Sentinel constructor for L0/L1 hot-path pre-allocation.
     * <p>Hardcodes {@code enableSuppression=false} and {@code writableStackTrace=false}
     * to prevent callers from accidentally enabling stack-trace generation and
     * breaking the zero-allocation contract. Uses a static pre-allocated
     * {@link #SENTINEL_ARGS} array — <strong>no heap allocation occurs</strong>.
     * The {@code traceId} and {@code timestamp} fields are assigned JVM constants
     * (see {@code ExerisKernelException} Sentinel constructor contract).
     *
     * <p><strong>Usage:</strong> pre-allocate once as a static final field and rethrow:
     * <pre>{@code
     * private static final TlsHandshakeException SENTINEL =
     *         new TlsHandshakeException();
     * }</pre>
     */
    public TlsHandshakeException() {
        super(KernelErrorCodes.EX_NET_2001, MESSAGE, null, false, false, SENTINEL_ARGS);
    }

    /**
     * Non-sentinel constructor with a dynamic detail string.
     * <p><strong>NOT zero-allocation</strong> — the {@code detail} String is captured
     * inside a freshly allocated {@code Object[]} for binary telemetry. Use the true
     * Sentinel path ({@link #TlsHandshakeException()}) when zero-allocation is required.
     *
     * @param detail             static message fragment; never formatted at runtime
     * @param enableSuppression  whether suppression is enabled
     * @param writableStackTrace whether the stack trace should be writable
     */
    public TlsHandshakeException(String detail, boolean enableSuppression, boolean writableStackTrace) {
        super(KernelErrorCodes.EX_NET_2001, MESSAGE, null,
                enableSuppression, writableStackTrace, new Object[]{-1, detail});
    }
}
