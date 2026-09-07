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
 * JFR lifecycle event emitted from {@code HttpStreamEngine.open(...)} when a server-push (SSE)
 * stream is opened and its response head has been written (ADR-043 obligation 8).
 *
 * <p>Single-phase commit ({@code @StackTrace(false)}): construct, set, commit with no blocking
 * operation in between — safe to emit from the stream's virtual thread (no carrier-bound
 * {@code EventWriter} straddle, the {@code ConnectionAcquireEvent} precedent).
 *
 * @since 0.10
 */
@Name("eu.exeris.kernel.http.StreamOpened")
@Label("HTTP Stream Opened")
@Category({"Exeris Kernel", "HTTP", "Streaming"})
@Description("A server-push (SSE) stream was opened and its response head written.")
@StackTrace(false)
public final class StreamOpenedEvent extends Event {

    /**
     * Identifies the transport stream that opened; the same value {@code TransportStream.streamId()}
     * returns for that stream.
     */
    @Label("Stream ID")
    public long streamId;

    /**
     * {@code true} when the stream was opened with an auth-expiry deadline greater than zero and is
     * therefore subject to fail-closed enforcement on JWT expiry; {@code false} when no such deadline
     * was configured for the stream (ADR-043 obligation 6).
     */
    @Label("Has Auth Deadline")
    @Description("True when the stream carries a JWT-expiry fail-closed deadline (ADR-043 obligation 6).")
    public boolean hasAuthDeadline;

    /**
     * Creates an unrecorded event.
     *
     * <p>{@link #emit} assigns the public fields and calls {@link Event#commit()}. An instance that is never
     * committed contributes nothing to a recording.
     */
    public StreamOpenedEvent() {
        // Declared, not added: the implicit no-arg constructor, written out so it can carry a comment.
        super();
    }

    /**
     * Emits a stream-opened event.
     *
     * @param streamId        the transport stream identifier
     * @param hasAuthDeadline whether an auth-expiry deadline governs this stream
     */
    public static void emit(long streamId, boolean hasAuthDeadline) {
        if (!FlightRecorder.isInitialized()) {
            return;
        }
        StreamOpenedEvent event = new StreamOpenedEvent();
        if (!event.isEnabled()) {
            return;
        }
        event.streamId = streamId;
        event.hasAuthDeadline = hasAuthDeadline;
        event.commit();
    }
}
