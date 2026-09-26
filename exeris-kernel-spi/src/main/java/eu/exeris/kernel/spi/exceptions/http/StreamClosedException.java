/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.spi.exceptions.http;

import eu.exeris.kernel.spi.exceptions.ExerisKernelException;
import eu.exeris.kernel.spi.exceptions.KernelErrorCodes;

/**
 * Thrown by {@link eu.exeris.kernel.spi.http.HttpStreamExchange#emit(eu.exeris.kernel.spi.http.StreamEvent)}
 * when the stream is no longer writable — peer disconnect, graceful/abortive close, or fail-closed
 * teardown on token expiry (ADR-043).
 *
 * <h2>Loom-Idiomatic Disconnect Signal</h2>
 * <p>Streaming handlers run an imperative emit loop on a single virtual thread. Rather than polling a
 * separate {@code awaitDisconnect()} affordance (which would risk a parked-VT leak), the disconnect is
 * surfaced as this <strong>unchecked</strong> throw: the loop exits naturally when {@code emit} throws,
 * and the engine reclaims the stream. Because it is unchecked, it does not pollute the
 * {@link eu.exeris.kernel.spi.http.HttpStreamHandler#handle} signature.
 *
 * <h2>Cause Discrimination</h2>
 * <p>The carried {@code EX-HTTP-*} code distinguishes <em>why</em> the stream closed while keeping a
 * single exception type for the handler to handle:
 * <ul>
 *   <li>{@link #peerDisconnect(long)}  → {@value KernelErrorCodes#EX_HTTP_4011} (emit after close)</li>
 *   <li>{@link #authExpired(long, long)} → {@value KernelErrorCodes#EX_HTTP_4012} (token expired mid-stream)</li>
 * </ul>
 *
 * <h2>Zero-Allocation Contract (The Wall)</h2>
 * <p>Follows the {@link ExerisKernelException} Glass-Box pattern: stream-scoped counters are stored in
 * {@link #rawArgs()} as typed primitives, never as formatted strings, and never event payload or token
 * content.
 *
 * <h2>rawArgs Binary Layout</h2>
 * <pre>
 * EX-HTTP-4011 (peer disconnect / emit-after-close):
 *   index 0 → long eventsEmitted
 *
 * EX-HTTP-4012 (token expired mid-stream, fail-closed per ADR-012 §5):
 *   index 0 → long streamAgeMillis
 *   index 1 → long eventsEmitted
 * </pre>
 *
 * @since 0.10
 */
public final class StreamClosedException extends ExerisKernelException {

    private static final String MSG_PEER_DISCONNECT = "HttpStreamExchange emit on closed stream";
    private static final String MSG_AUTH_EXPIRED = "HttpStreamExchange closed — token expired mid-stream";

    /**
     * General-purpose constructor — used when no specific factory method matches the scenario.
     *
     * @param errorCode {@code EX-HTTP-*} code; must not be {@code null}
     * @param message   static message template; no runtime formatting
     * @param rawArgs   raw stream-scoped arguments for Glass-Box telemetry
     */
    public StreamClosedException(String errorCode, String message, Object... rawArgs) {
        super(errorCode, message, rawArgs);
    }

    // -------------------------------------------------------------------------
    // Factory methods
    // -------------------------------------------------------------------------

    /**
     * Creates a {@code StreamClosedException} for an {@code emit} on a stream that has already closed
     * (peer disconnect, graceful close, or abortive teardown).
     *
     * @param eventsEmitted events successfully emitted before the close
     * @return a new exception carrying {@value KernelErrorCodes#EX_HTTP_4011}
     */
    public static StreamClosedException peerDisconnect(long eventsEmitted) {
        return new StreamClosedException(KernelErrorCodes.EX_HTTP_4011, MSG_PEER_DISCONNECT, eventsEmitted);
    }

    /**
     * Creates a {@code StreamClosedException} for a stream closed because the authenticated principal's
     * token expired while the stream was held open (fail-closed per ADR-012 §5).
     *
     * @param streamAgeMillis elapsed time since stream open at expiry
     * @param eventsEmitted   events successfully emitted before expiry
     * @return a new exception carrying {@value KernelErrorCodes#EX_HTTP_4012}
     */
    public static StreamClosedException authExpired(long streamAgeMillis, long eventsEmitted) {
        return new StreamClosedException(
                KernelErrorCodes.EX_HTTP_4012, MSG_AUTH_EXPIRED, streamAgeMillis, eventsEmitted);
    }
}
