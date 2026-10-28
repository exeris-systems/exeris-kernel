/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.core.transport.jfr;

import jdk.jfr.Category;
import jdk.jfr.Description;
import jdk.jfr.Event;
import jdk.jfr.FlightRecorder;
import jdk.jfr.Label;
import jdk.jfr.Name;
import jdk.jfr.StackTrace;

/**
 * JFR event emitted when a {@code TransportStream#write} call finds the stream's outbound queue at
 * its bound and is about to park the calling thread until the peer takes enough bytes.
 *
 * <p>Single-phase commit ({@code @StackTrace(false)}): construct, set and commit BEFORE the park.
 * A blocking park must never sit between {@code begin()} and {@code commit()} on a virtual thread,
 * because the carrier-bound event writer would straddle it. The event therefore records the park
 * decision, not how long the park lasted.
 *
 * <p>Emitted once per parking {@code write} call, before its first park; later wake-ups of the same
 * call do not emit again.
 *
 * @since 0.13
 */
@Name("eu.exeris.kernel.core.transport.WriteBackpressurePark")
@Label("Transport Write Backpressure Park")
@Category({"Exeris Kernel", "Transport", "Backpressure"})
@Description("A write parked its calling thread because the stream's outbound queue reached its bound.")
@StackTrace(false)
public final class TransportWriteParkEvent extends Event {

    /** Transport-level identifier of the stream whose writer parked. */
    @Label("Stream ID")
    public long streamId;

    /** Bytes queued on the stream that the peer had not yet taken when the park decision was made. */
    @Label("Queued Bytes")
    @Description("Outbound bytes not yet taken by the peer at park time.")
    public long queuedBytes;

    /** The stream's configured outbound bound, in bytes. */
    @Label("Bound Bytes")
    @Description("The stream's configured maximum queued write bytes.")
    public long boundBytes;

    /**
     * Creates an unrecorded event.
     *
     * <p>{@link #emit} assigns the public fields and calls {@link Event#commit()}. An instance that is never
     * committed contributes nothing to a recording.
     */
    public TransportWriteParkEvent() {
        // Declared, not added: the implicit no-arg constructor, written out so it can carry a comment.
        super();
    }

    /**
     * Emits a write-park event. Call BEFORE parking the calling thread.
     *
     * <p>Guards on {@link FlightRecorder#isInitialized()} to avoid allocation when JFR is off.
     *
     * @param streamId    the transport stream identifier
     * @param queuedBytes outbound bytes queued at park time
     * @param boundBytes  the stream's configured outbound bound
     */
    public static void emit(long streamId, long queuedBytes, long boundBytes) {
        if (!FlightRecorder.isInitialized()) {
            return;
        }
        TransportWriteParkEvent event = new TransportWriteParkEvent();
        if (!event.isEnabled()) {
            return;
        }
        event.streamId = streamId;
        event.queuedBytes = queuedBytes;
        event.boundBytes = boundBytes;
        event.commit();
    }
}
