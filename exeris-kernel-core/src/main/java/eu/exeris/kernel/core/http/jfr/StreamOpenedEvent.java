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
 * JFR lifecycle event emitted when a server-push (SSE) stream is opened and its response head
 * has been written (ADR-043 obligation 8).
 *
 * <p>Single-phase commit ({@code @StackTrace(false)}): construct, set, commit with no blocking
 * operation in between — safe to emit from the stream's virtual thread (no carrier-bound
 * {@code EventWriter} straddle, the {@code ConnectionAcquireEvent} precedent).
 *
 * @since 0.10.0
 */
@Name("eu.exeris.kernel.http.StreamOpened")
@Label("HTTP Stream Opened")
@Category({"Exeris Kernel", "HTTP", "Streaming"})
@Description("A server-push (SSE) stream was opened and its response head written.")
@StackTrace(false)
public final class StreamOpenedEvent extends Event {

    @Label("Stream ID")
    public long streamId;

    @Label("Has Auth Deadline")
    @Description("True when the stream carries a JWT-expiry fail-closed deadline (ADR-043 obligation 6).")
    public boolean hasAuthDeadline;

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
