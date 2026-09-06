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
 * JFR event emitted when an incoming stream is rejected at the transport edge, from either of
 * two independent admission paths that share this one event: PAQS ({@code StreamLoadShedder.shed},
 * reached when the {@link eu.exeris.kernel.core.transport.scheduler.AdmissionController} sheds
 * for memory pressure or active-stream capacity), and the SSE stream-open admission gate
 * ({@code StreamAdmissionController.admit}, reached when the injected
 * {@link eu.exeris.kernel.core.memory.ResourceArbiter.Action#SHED_LOAD} decision fires).
 *
 * <h2>Error Code Mapping (Glass-Box Telemetry)</h2>
 * <pre>
 *   EX-NET-4006 — stream rejected because the PAQS queue has reached the saturated threshold
 * </pre>
 * <p>Only the SSE path actually throws a {@code TransportException} carrying that code back to
 * a caller; the PAQS path closes the stream directly with no exception thrown to the carrier
 * thread — for that path this event, not a caught exception, is the observable signal.
 *
 * <h2>JFR-First Principle</h2>
 * <p>Load-shedding at the transport edge is a system health signal, not an application error.
 * Emitting this as a JFR event instead of a log gives sub-microsecond overhead and direct
 * correlation to the {@code ResourceArbiterDecisionEvent} on the timeline.
 *
 * @since 0.5
 */
@Name("eu.exeris.kernel.core.transport.StreamShed")
@Label("Stream Shed")
@Category({"Exeris Kernel", "Transport", "PAQS"})
@Description("Emitted when PAQS rejects an incoming stream at the transport edge due to resource pressure.")
@StackTrace(false)
public final class StreamShedEvent extends Event {

    /** SPI stream identifier of the shed stream. */
    @Label("Stream ID")
    public long streamId;

        /**
         * {@link eu.exeris.kernel.spi.transport.StreamPriority} enum constant name of the shed
         * stream, or {@code NORMAL} when no priority was resolved.
         */
    @Label("Priority")
    public String priority;

    /**
     * Name of the decision that caused the shed. Carries an
     * {@link eu.exeris.kernel.core.transport.scheduler.AdmissionController.Decision} name
     * ({@code SHED_CAPACITY} or {@code SHED_MEMORY}) when emitted from the PAQS admission path,
     * or {@link eu.exeris.kernel.core.memory.ResourceArbiter.Action#SHED_LOAD} when emitted from
     * the SSE stream-open admission path — two distinct enums that happen to share this one
     * {@code String} field.
     */
    @Label("Shed Reason")
    @Description("The AdmissionController Decision that triggered shedding: SHED_CAPACITY, SHED_MEMORY, etc.")
    public String shedReason;

    /** Name of the transport engine that shed the stream. */
    @Label("Engine Name")
    public String engineName;

    /**
     * Active-stream count at the moment of shedding, read from the specific counter of whichever
     * admission path emitted this event: {@code AdmissionController.activeStreamCount()} on the
     * PAQS path, or the SSE stream-open slot counter ({@code CommunityHttpStreamDispatcher}'s
     * own count of open streaming slots) on the streaming-admission path — two independent
     * counters, not one kernel-wide total.
     */
    @Label("Active Stream Count")
    @Description("Number of virtual threads actively handling streams at the moment of shedding")
    public int activeStreamCount;

    /**
     * Factory: emits a stream shed event.
     *
     * @param streamId          the SPI stream identifier
     * @param priority          the {@link eu.exeris.kernel.spi.transport.StreamPriority} name
     * @param shedReason        the {@link eu.exeris.kernel.core.transport.scheduler.AdmissionController.Decision} name
     * @param engineName        the engine name
     * @param activeStreamCount number of concurrently active streams at shed time
     */
    public static void emit(long streamId,
                            String priority,
                            String shedReason,
                            String engineName,
                            int activeStreamCount) {
        if (!FlightRecorder.isInitialized()) {
            return;
        }
        StreamShedEvent evt = new StreamShedEvent();
        if (!evt.isEnabled()) {
            return;
        }
        evt.streamId = streamId;
        evt.priority = priority;
        evt.shedReason = shedReason;
        evt.engineName = engineName;
        evt.activeStreamCount = activeStreamCount;
        evt.commit();
    }
}
