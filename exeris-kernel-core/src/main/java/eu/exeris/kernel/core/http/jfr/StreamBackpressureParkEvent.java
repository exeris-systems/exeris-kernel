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
 * JFR hot-path event emitted when {@code emit()} parks the emitting virtual thread because the
 * egress credit window is full (ADR-043 obligation 4 / 8). Park-the-VT under backpressure is the
 * No-Waste-Compute alternative to an unbounded on-heap queue.
 *
 * <p>Single-phase commit ({@code @StackTrace(false)}): construct, set, commit BEFORE the actual
 * park. The blocking park MUST NOT sit between {@code begin()} and {@code commit()} on a virtual
 * thread (carrier-bound {@code EventWriter} straddle → SIGSEGV; the {@code ConnectionAcquireEvent}
 * precedent). This event records the park decision, not its duration.
 *
 * @since 0.10.0
 */
@Name("eu.exeris.kernel.http.StreamBackpressurePark")
@Label("HTTP Stream Backpressure Park")
@Category({"Exeris Kernel", "HTTP", "Streaming"})
@Description("emit() parked the emitting virtual thread on a full egress credit window.")
@StackTrace(false)
public final class StreamBackpressureParkEvent extends Event {

    @Label("Stream ID")
    public long streamId;

    @Label("Outstanding Bytes")
    @Description("Bytes outstanding (queued but unacknowledged by the transport) at park time.")
    public long outstandingBytes;

    @Label("Events Emitted")
    @Description("Number of SSE events successfully emitted before the park.")
    public long eventsEmitted;

    /**
     * Emits a backpressure-park event. Call BEFORE parking the virtual thread.
     *
     * @param streamId         the transport stream identifier
     * @param outstandingBytes outstanding queued bytes at park time
     * @param eventsEmitted    events emitted before the park
     */
    public static void emit(long streamId, long outstandingBytes, long eventsEmitted) {
        if (!FlightRecorder.isInitialized()) {
            return;
        }
        StreamBackpressureParkEvent event = new StreamBackpressureParkEvent();
        if (!event.isEnabled()) {
            return;
        }
        event.streamId = streamId;
        event.outstandingBytes = outstandingBytes;
        event.eventsEmitted = eventsEmitted;
        event.commit();
    }
}
