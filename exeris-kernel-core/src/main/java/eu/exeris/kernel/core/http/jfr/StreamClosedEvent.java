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
 * JFR lifecycle event emitted from {@code HttpStreamEngine.close()} when a server-push (SSE)
 * stream is gracefully closed — the handler called {@code close()} and a clean end-of-stream
 * was written (ADR-043 obligation 8).
 *
 * <p>Single-phase commit ({@code @StackTrace(false)}): construct, set, commit with no blocking
 * operation in between — safe to emit from the stream's virtual thread.
 *
 * @since 0.10
 */
@Name("eu.exeris.kernel.http.StreamClosed")
@Label("HTTP Stream Closed")
@Category({"Exeris Kernel", "HTTP", "Streaming"})
@Description("A server-push (SSE) stream was gracefully closed (clean end-of-stream).")
@StackTrace(false)
public final class StreamClosedEvent extends Event {

    /**
     * Identifies the transport stream that closed; the same value {@code TransportStream.streamId()}
     * returns for that stream.
     */
    @Label("Stream ID")
    public long streamId;

    /**
     * Count of SSE events written to the stream before this graceful close.
     */
    @Label("Events Emitted")
    @Description("Number of SSE events successfully emitted before the graceful close.")
    public long eventsEmitted;

    /**
     * Creates an unrecorded event.
     *
     * <p>{@link #emit} assigns the public fields and calls {@link Event#commit()}. An instance that is never
     * committed contributes nothing to a recording.
     */
    public StreamClosedEvent() {
        // Declared, not added: the implicit no-arg constructor, written out so it can carry a comment.
        super();
    }

    /**
     * Emits a graceful stream-closed event.
     *
     * @param streamId      the transport stream identifier
     * @param eventsEmitted events emitted before close
     */
    public static void emit(long streamId, long eventsEmitted) {
        if (!FlightRecorder.isInitialized()) {
            return;
        }
        StreamClosedEvent event = new StreamClosedEvent();
        if (!event.isEnabled()) {
            return;
        }
        event.streamId = streamId;
        event.eventsEmitted = eventsEmitted;
        event.commit();
    }
}
