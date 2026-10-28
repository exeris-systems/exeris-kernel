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
 * <p>Emitted from {@code HttpStreamEngine.awaitCredit}, or from the egress's credit wait on a protocol
 * with its own flow control, once per park entry, at the point where
 * queuing the next frame would push outstanding bytes over the stream's credit window — before
 * the virtual thread actually parks. A single wide park can straddle several unpark/re-check
 * cycles without this event firing again; it marks entry into the park, not each wakeup.
 *
 * @since 0.10
 */
@Name("eu.exeris.kernel.http.StreamBackpressurePark")
@Label("HTTP Stream Backpressure Park")
@Category({"Exeris Kernel", "HTTP", "Streaming"})
@Description("emit() parked the emitting virtual thread on a full egress credit window.")
@StackTrace(false)
public final class StreamBackpressureParkEvent extends Event {

    /** Transport-level identifier of the stream that parked ({@code TransportStream#streamId()}). */
    @Label("Stream ID")
    public long streamId;

    /**
     * The protocol's id for the stream on its connection: the HTTP/2 stream id, {@code 0} for HTTP/1.1,
     * where the transport stream is the whole connection. Together with {@code streamId} it tells two
     * streams of one connection apart.
     */
    @Label("Protocol Stream ID")
    @Description("The protocol's stream id on its connection; 0 for HTTP/1.1.")
    public int protocolStreamId;

    /**
     * Bytes outstanding (queued to the transport but not yet released) at the moment the park
     * decision was made — not updated as the park continues or as credit is later released.
     */
    @Label("Outstanding Bytes")
    @Description("Bytes outstanding (queued but unacknowledged by the transport) at park time.")
    public long outstandingBytes;

    /**
     * The credit that was available when the park decision was made: the smaller of the stream's and
     * the connection's flow-control window on HTTP/2, {@code -1} on HTTP/1.1, where credit is the
     * outstanding-bytes bound.
     */
    @Label("Window Bytes")
    @Description("Smaller of the stream and connection flow-control windows at park time; -1 on HTTP/1.1.")
    public long windowBytes;

    /** Number of SSE events successfully emitted on this stream before this park. */
    @Label("Events Emitted")
    @Description("Number of SSE events successfully emitted before the park.")
    public long eventsEmitted;

    /**
     * Creates an unrecorded event.
     *
     * <p>{@link #emit} assigns the public fields and calls {@link Event#commit()}. An instance that is never
     * committed contributes nothing to a recording.
     */
    public StreamBackpressureParkEvent() {
        // Declared, not added: the implicit no-arg constructor, written out so it can carry a comment.
        super();
    }

    /**
     * Emits a backpressure-park event. Call BEFORE parking the virtual thread.
     *
     * @param streamId         the transport stream identifier
     * @param outstandingBytes outstanding queued bytes at park time
     * @param eventsEmitted    events emitted before the park
     */
    public static void emit(long streamId, long outstandingBytes, long eventsEmitted) {
        emit(streamId, 0, outstandingBytes, -1L, eventsEmitted);
    }

    /**
     * Emits a backpressure-park event for a stream of a multiplexed protocol. Call BEFORE parking.
     *
     * @param streamId         the transport stream identifier
     * @param protocolStreamId the protocol's stream id on its connection, {@code 0} where there is none
     * @param outstandingBytes outstanding queued bytes at park time
     * @param windowBytes      the available flow-control window at park time, {@code -1} where there is none
     * @param eventsEmitted    events emitted before the park
     */
    public static void emit(long streamId, int protocolStreamId, long outstandingBytes, long windowBytes,
                            long eventsEmitted) {
        if (!FlightRecorder.isInitialized()) {
            return;
        }
        StreamBackpressureParkEvent event = new StreamBackpressureParkEvent();
        if (!event.isEnabled()) {
            return;
        }
        event.streamId = streamId;
        event.protocolStreamId = protocolStreamId;
        event.windowBytes = windowBytes;
        event.outstandingBytes = outstandingBytes;
        event.eventsEmitted = eventsEmitted;
        event.commit();
    }
}
