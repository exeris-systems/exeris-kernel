 /*
 * Copyright (C) 2025-2026 Exeris. All rights reserved.
 *
 * This code is part of the Exeris Systems.
 * Distributed under the proprietary Exeris Software License.
 * Unauthorized copying or distribution is prohibited.
 */
package eu.exeris.kernel.spi.crypto;

/**
 * SPI: Immutable result of a single TLS shutdown step.
 *
 * <h2>Valhalla Readiness</h2>
 * <p>Declared as {@code value record} — no object header overhead. The
 * {@code sentCloseNotify}/{@code receivedCloseNotify} booleans are packed
 * together with the status ordinal in a flattened layout.
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
     * Used when SSL_shutdown() returns 0 — local close-notify sent, peer not yet acknowledged.
     *
     * @param sent     whether local close-notify was sent ({@code SSL_SENT_SHUTDOWN} flag)
     * @param received whether peer close-notify was received ({@code SSL_RECEIVED_SHUTDOWN} flag)
     * @return partial shutdown result
     */
    public static TlsShutdownResult partial(boolean sent, boolean received) {
        return new TlsShutdownResult(Status.NEED_MORE_IO, sent, received);
    }

    /**
     * Creates a result from OpenSSL {@code SSL_shutdown()} return value and
     * {@code SSL_get_shutdown()} flags.
     *
     * @param retVal         return value of {@code SSL_shutdown()}
     * @param shutdownStatus flags from {@code SSL_get_shutdown()} (bit 0 = SENT, bit 1 = RECEIVED)
     * @return mapped shutdown result
     */
    public static TlsShutdownResult fromReturnValue(int retVal, int shutdownStatus) {
        boolean sent     = (shutdownStatus & 1) != 0; // SSL_SENT_SHUTDOWN
        boolean received = (shutdownStatus & 2) != 0; // SSL_RECEIVED_SHUTDOWN
        return switch (retVal) {
            case 1  -> COMPLETE;
            case 0  -> partial(sent, received);
            default -> ERROR;
        };
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


