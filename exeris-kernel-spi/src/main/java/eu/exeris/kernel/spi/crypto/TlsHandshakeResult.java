/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.spi.crypto;

/**
 * SPI: Immutable result of a single TLS handshake step.
 *
 * <h2>Valhalla Readiness</h2>
 * <p>Immutable data carrier, declared {@code value record} on the `preview` line (JEP 401, preview
 * in JDK 28). The distributed line compiles the same source as an identity {@code record}; the
 * modifier is the only difference, and it is asserted by {@code Class::isValue} in this carrier's
 * ValhallaReadiness test rather than left to a one-time inspection.
 * A JVM is therefore free to flatten instances in arrays and as fields of other value types,
 * eliminating object headers on the heap — an opportunity, not a behaviour this contract assumes.
 *
 * <p><b>Allocation:</b> zero-alloc on hot path — every non-error outcome is one of the
 * pre-allocated constants {@link #COMPLETE}, {@link #NEED_UNWRAP} and {@link #NEED_WRAP};
 * only {@link #error(int)} constructs a new instance.
 *
 * @param status          the semantic outcome of this handshake step
 * @param nativeErrorCode provider-specific error code (0 for non-error outcomes);
 *                        interpretation is left to the implementation tier
 * @apiNote Compare with the predicates ({@link #isComplete()}, {@link #needsUnwrap()},
 *          {@link #needsWrap()}, {@link #isError()}) rather than by identity against the
 *          constants: an error result is a fresh instance and never one of them.
 * @since 0.5
 */
public value record TlsHandshakeResult(Status status, int nativeErrorCode) {

    /** Pre-allocated singleton: handshake complete. */
    public static final TlsHandshakeResult COMPLETE    = new TlsHandshakeResult(Status.COMPLETE, 0);

    /** Pre-allocated singleton: peer must send data before handshake can continue. */
    public static final TlsHandshakeResult NEED_UNWRAP = new TlsHandshakeResult(Status.NEED_UNWRAP, 0);

    /** Pre-allocated singleton: local side must send data before handshake can continue. */
    public static final TlsHandshakeResult NEED_WRAP   = new TlsHandshakeResult(Status.NEED_WRAP, 0);

    /**
     * Creates an error result with the provider-specific native error code.
     *
     * @param nativeErrorCode provider-specific error code returned by the underlying engine
     * @return new error result (not cached — errors are rare)
     */
    public static TlsHandshakeResult error(int nativeErrorCode) {
        return new TlsHandshakeResult(Status.ERROR, nativeErrorCode);
    }

    /**
     * Indicates that no further handshake step is required and the session may carry
     * application data.
     *
     * @return {@code true} when {@link #status()} is {@link Status#COMPLETE}
     */
    public boolean isComplete() {
        return status == Status.COMPLETE;
    }

    /**
     * Indicates that the handshake is blocked until inbound bytes from the peer are decrypted.
     *
     * @return {@code true} when {@link #status()} is {@link Status#NEED_UNWRAP}, meaning the
     *         caller must read from the transport and unwrap before stepping again
     */
    public boolean needsUnwrap() {
        return status == Status.NEED_UNWRAP;
    }

    /**
     * Indicates that the handshake is blocked until the pending outbound flight is produced
     * and transmitted.
     *
     * @return {@code true} when {@link #status()} is {@link Status#NEED_WRAP}, meaning the
     *         caller must wrap and flush before stepping again
     */
    public boolean needsWrap() {
        return status == Status.NEED_WRAP;
    }

    /**
     * Indicates that the handshake failed fatally and the session cannot be recovered.
     *
     * @return {@code true} when {@link #status()} is {@link Status#ERROR}, in which case
     *         {@link #nativeErrorCode()} carries the provider's own code
     */
    public boolean isError() {
        return status == Status.ERROR;
    }

    /**
     * Semantic outcome of a single handshake step.
     * Maps to {@link TlsStatus} for transport-layer consumers.
     */
    public enum Status {
        /** Handshake completed — transition TLS session to {@link TlsPhase#HANDSHAKE_COMPLETE}. */
        COMPLETE,
        /** Blocked on inbound data — call unwrap() when bytes arrive. */
        NEED_UNWRAP,
        /** Blocked on outbound flush — call wrap() to produce bytes. */
        NEED_WRAP,
        /** Fatal error — transition TLS session to {@link TlsPhase#ERROR}. */
        ERROR
    }
}


