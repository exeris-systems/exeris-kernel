/*
 * Copyright (C) 2025-2026 Exeris. All rights reserved.
 *
 * This code is part of the Exeris Systems.
 * Distributed under the proprietary Exeris Software License.
 * Unauthorized copying or distribution is prohibited.
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
 * JFR event emitted by the PAQS Scheduler when an incoming stream is shed at the
 * transport edge due to memory pressure or thread capacity exhaustion.
 *
 * <h2>Error Code Mapping (Black-Box Telemetry)</h2>
 * <pre>
 *   EX-NET-4006 — stream rejected because the PAQS queue has reached the saturated threshold
 * </pre>
 *
 * <h2>JFR-First Principle</h2>
 * <p>Load-shedding at the transport edge is a system health signal, not an application error.
 * Emitting this as a JFR event instead of a log gives sub-microsecond overhead and direct
 * correlation to the {@code ResourceArbiterDecisionEvent} on the timeline.
 *
 * @since 0.5.0
 */
@Name("eu.exeris.kernel.core.transport.StreamShed")
@Label("Stream Shed")
@Category({"Exeris Kernel", "Transport", "PAQS"})
@Description("Emitted when PAQS rejects an incoming stream at the transport edge due to resource pressure.")
@StackTrace(false)
public final class StreamShedEvent extends Event {

    @Label("Stream ID")
    public long streamId;

    @Label("Priority")
    public String priority;

    @Label("Shed Reason")
    @Description("The AdmissionController Decision that triggered shedding: SHED_CAPACITY, SHED_MEMORY, etc.")
    public String shedReason;

    @Label("Engine Name")
    public String engineName;

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
        if (evt.isEnabled()) {
            evt.streamId = streamId;
            evt.priority = priority;
            evt.shedReason = shedReason;
            evt.engineName = engineName;
            evt.activeStreamCount = activeStreamCount;
            evt.commit();
        }
    }
}
