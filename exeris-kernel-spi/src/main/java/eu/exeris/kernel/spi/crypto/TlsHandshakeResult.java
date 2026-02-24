/*
 * Copyright (C) 2025-2026 Exeris. All rights reserved.
 *
 * This code is part of the Exeris Systems.
 * Distributed under the proprietary Exeris Software License.
 * Unauthorized copying or distribution is prohibited.
 */
package eu.exeris.kernel.spi.crypto;

/**
 * SPI: Immutable result of a single TLS handshake step.
 *
 * <h2>Valhalla Readiness</h2>
 * <p>Immutable data carrier and candidate for {@code value record} once JEP 401
 * is mainline — at that point JVMs will be able to flatten instances in arrays and
 * as fields of other value types, eliminating object headers on the heap.
 *
 * <h2>Zero-Allocation Contract</h2>
 * <p>Callers retrieve one of the three pre-allocated singleton instances
 * ({@link #COMPLETE}, {@link #NEED_UNWRAP}, {@link #NEED_WRAP}) via the static
 * factory methods. No {@code new} on the hot handshake path unless an error occurs.
 *
 * @param status          the semantic outcome of this handshake step
 * @param nativeErrorCode provider-specific error code (0 for non-error outcomes);
 *                        interpretation is left to the implementation tier
 * @since 0.5.0
 */
public record TlsHandshakeResult(Status status, int nativeErrorCode) {

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

    /** Returns {@code true} if the handshake is fully complete. */
    public boolean isComplete() {
        return status == Status.COMPLETE;
    }

    /** Returns {@code true} if the handshake cannot proceed without receiving data. */
    public boolean needsUnwrap() {
        return status == Status.NEED_UNWRAP;
    }

    /** Returns {@code true} if the handshake cannot proceed without sending data. */
    public boolean needsWrap() {
        return status == Status.NEED_WRAP;
    }

    /** Returns {@code true} if the handshake failed fatally. */
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


