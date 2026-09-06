/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.core.http.jfr;

import jdk.jfr.Category;
import jdk.jfr.Description;
import jdk.jfr.Event;
import jdk.jfr.FlightRecorder;
import jdk.jfr.Label;
import jdk.jfr.Name;
import jdk.jfr.StackTrace;

/**
 * JFR lifecycle event emitted when a server-push (SSE) stream is torn down abortively on a dead
 * transport key — peer disconnect or fail-closed token expiry (ADR-043 obligation 8).
 *
 * <p>Emitted from {@code HttpStreamEngine.abortiveTeardown}, reached either when a write to the
 * transport fails because the peer is gone (from {@code transferFrame}), or when {@code emit()}
 * finds the configured auth deadline has passed (from {@code failClosedIfAuthExpired}). The
 * stream is reset before this event commits.
 *
 * <p>Single-phase commit ({@code @StackTrace(false)}): construct, set, commit with no blocking
 * operation in between — safe to emit from the stream's virtual thread.
 *
 * @since 0.10
 */
@Name("eu.exeris.kernel.http.StreamAbortiveTeardown")
@Label("HTTP Stream Abortive Teardown")
@Category({"Exeris Kernel", "HTTP", "Streaming"})
@Description("A server-push (SSE) stream was abortively torn down on a dead key (#202 path).")
@StackTrace(false)
public final class StreamAbortiveTeardownEvent extends Event {

    /** Transport-level identifier of the stream that was torn down ({@code TransportStream#streamId()}). */
    @Label("Stream ID")
    public long streamId;

    /**
     * Structured kernel error code: {@code EX-HTTP-4011} (peer disconnect) or {@code EX-HTTP-4012}
     * (auth expiry) — the only two codes {@code abortiveTeardown} is reached with.
     */
    @Label("Error Code")
    @Description("Structured kernel error code: EX-HTTP-4011 (peer disconnect) or EX-HTTP-4012 (auth expiry).")
    public String errorCode;

    /** Number of SSE events successfully emitted on this stream before the abortive teardown. */
    @Label("Events Emitted")
    @Description("Number of SSE events successfully emitted before the abortive teardown.")
    public long eventsEmitted;

    /**
     * Emits an abortive-teardown event.
     *
     * @param streamId      the transport stream identifier
     * @param errorCode     the structured kernel error code that ended the stream
     * @param eventsEmitted events emitted before teardown
     */
    public static void emit(long streamId, String errorCode, long eventsEmitted) {
        if (!FlightRecorder.isInitialized()) {
            return;
        }
        StreamAbortiveTeardownEvent event = new StreamAbortiveTeardownEvent();
        if (!event.isEnabled()) {
            return;
        }
        event.streamId = streamId;
        event.errorCode = errorCode;
        event.eventsEmitted = eventsEmitted;
        event.commit();
    }
}
