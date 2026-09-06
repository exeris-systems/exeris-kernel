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
 * JFR event emitted by the PAQS Scheduler when a new {@link eu.exeris.kernel.spi.transport.TransportStream}
 * is admitted and a Virtual Thread is spawned to handle it.
 *
 * <h2>JFR-First Principle</h2>
 * <p>This event is the entry-point signal in the "Glass Box" telemetry pipeline.
 * It fires exactly once per admitted stream, providing correlation with downstream
 * business events via {@code streamId}. The {@link jdk.jfr.FlightRecorder} guard
 * ensures zero overhead when recording is disabled.
 *
 * @since 0.5
 */
@Name("eu.exeris.kernel.core.transport.StreamAccepted")
@Label("Stream Accepted")
@Category({"Exeris Kernel", "Transport", "PAQS"})
@Description("Emitted when PAQS admits an incoming stream and spawns a Virtual Thread to handle it.")
@StackTrace(false)
public final class StreamAcceptedEvent extends Event {

    /** SPI stream identifier of the admitted stream. */
    @Label("Stream ID")
    public long streamId;

        /**
         * {@link eu.exeris.kernel.spi.transport.StreamPriority} enum constant name resolved for
         * this stream at admission.
         */
    @Label("Priority")
    public String priority;

    /** Name of the transport engine that admitted the stream. */
    @Label("Engine Name")
    public String engineName;

        /**
         * Name of the virtual thread spawned to run the stream handler; format
         * {@code paqs/<engineName>/<priority>/<streamId>}.
         */
    @Label("Virtual Thread Name")
    public String virtualThreadName;

    /**
     * Factory: emits a stream accepted event.
     *
     * @param streamId          the SPI stream identifier
     * @param priority          the {@link eu.exeris.kernel.spi.transport.StreamPriority} name
     * @param engineName        the engine name from {@link eu.exeris.kernel.spi.transport.TransportEngine#engineName()}
     * @param virtualThreadName name assigned to the spawned virtual thread
     */
    public static void emit(long streamId, String priority, String engineName, String virtualThreadName) {
        if (!FlightRecorder.isInitialized()) {
            return;
        }
        StreamAcceptedEvent evt = new StreamAcceptedEvent();
        if (evt.isEnabled()) {
            evt.streamId = streamId;
            evt.priority = priority;
            evt.engineName = engineName;
            evt.virtualThreadName = virtualThreadName;
            evt.commit();
        }
    }
}
