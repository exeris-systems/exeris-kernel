/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.spi.crypto;

/**
 * SPI: Immutable result of a single TLS shutdown step.
 *
 * <h2>Valhalla Readiness</h2>
 * <p>Designed as a candidate for {@code value record} once JEP 401 is mainline.
 * At that point the {@code sentCloseNotify}/{@code receivedCloseNotify} booleans
 * and the status ordinal can be flattened to minimize header and pointer overhead.
 *
 * <h2>Zero-Allocation Contract</h2>
 * <p>{@link #COMPLETE} and {@link #ERROR} are pre-allocated singletons.
 * Partial-shutdown results carry two boolean flags and are cheap value types.
 *
 * @param status              semantic outcome of this shutdown step
 * @param sentCloseNotify     whether the local side has sent a TLS close-notify alert
 * @param receivedCloseNotify whether the peer's close-notify has been received
 * @since 0.5.0
 */
public record TlsShutdownResult(Status status,
                                boolean sentCloseNotify,
                                boolean receivedCloseNotify) {

    /** Pre-allocated singleton: graceful shutdown fully completed. */
    public static final TlsShutdownResult COMPLETE =
            new TlsShutdownResult(Status.COMPLETE, true, true);

    /** Pre-allocated singleton: fatal error during shutdown. */
    public static final TlsShutdownResult ERROR =
            new TlsShutdownResult(Status.ERROR, false, false);

    /**
     * Creates a partial-shutdown result.
     * Used when the underlying engine indicates that the local close-notify was sent
     * but the peer has not yet acknowledged — the caller must continue polling.
     *
     * @param sent     whether the local side has sent a close-notify alert
     * @param received whether the peer's close-notify has been received
     * @return partial shutdown result
     */
    public static TlsShutdownResult partial(boolean sent, boolean received) {
        return new TlsShutdownResult(Status.NEED_MORE_IO, sent, received);
    }


    /** Returns {@code true} if both close-notify alerts have been exchanged. */
    public boolean isComplete() {
        return status == Status.COMPLETE;
    }

    /** Returns {@code true} if further I/O is needed to complete the shutdown. */
    public boolean needsMoreIo() {
        return status == Status.NEED_MORE_IO;
    }

    /** Returns {@code true} if a fatal error occurred during shutdown. */
    public boolean isError() {
        return status == Status.ERROR;
    }

    /**
     * Semantic outcome of a single shutdown step.
     */
    public enum Status {
        /** Both sides have exchanged close-notify — transition to {@link TlsPhase#SHUTDOWN_COMPLETE}. */
        COMPLETE,
        /** Partial: close-notify sent, but peer has not responded yet — continue calling shutdown(). */
        NEED_MORE_IO,
        /** Fatal error — transition to {@link TlsPhase#ERROR}. */
        ERROR
    }
}


