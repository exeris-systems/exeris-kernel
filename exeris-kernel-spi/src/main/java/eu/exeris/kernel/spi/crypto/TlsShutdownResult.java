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
 * <p><b>Allocation:</b> zero-alloc on hot path for the two terminal outcomes — {@link #COMPLETE}
 * and {@link #ERROR} are pre-allocated constants; {@link #partial(boolean, boolean)} allocates
 * one small carrier per partial step.
 *
 * @param status              semantic outcome of this shutdown step
 * @param sentCloseNotify     whether the local side has sent a TLS close-notify alert
 * @param receivedCloseNotify whether the peer's close-notify has been received
 * @apiNote Compare with the predicates ({@link #isComplete()}, {@link #needsMoreIo()},
 *          {@link #isError()}) rather than by identity against the constants: a partial result is
 *          a fresh instance and never one of them.
 * @since 0.5
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


    /**
     * Indicates that both sides have exchanged close-notify and the session may be released.
     *
     * @return {@code true} when {@link #status()} is {@link Status#COMPLETE}
     */
    public boolean isComplete() {
        return status == Status.COMPLETE;
    }

    /**
     * Indicates that the shutdown is half-done and the caller must step it again.
     *
     * @return {@code true} when {@link #status()} is {@link Status#NEED_MORE_IO}; the two flags
     *         {@link #sentCloseNotify()} and {@link #receivedCloseNotify()} say which half is
     *         still outstanding
     */
    public boolean needsMoreIo() {
        return status == Status.NEED_MORE_IO;
    }

    /**
     * Indicates that the shutdown failed and no graceful close is reachable on this session.
     *
     * @return {@code true} when {@link #status()} is {@link Status#ERROR}
     */
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


