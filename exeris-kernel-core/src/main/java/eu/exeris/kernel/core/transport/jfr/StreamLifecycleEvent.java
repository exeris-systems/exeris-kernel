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
 * JFR event emitted by the PAQS Scheduler when a stream's Virtual Thread lifecycle
 * completes (cleanly or with an error).
 *
 * <h2>Stream Lifecycle Tracking</h2>
 * <p>Paired with {@link StreamAcceptedEvent}, this event closes the JFR timeline window
 * for a given {@code streamId}. The delta between {@link StreamAcceptedEvent} and
 * {@link StreamLifecycleEvent} represents the total Virtual Thread occupancy time for
 * the stream — a key metric for Virtual Thread density tuning.
 *
 * @since 0.5
 */
@Name("eu.exeris.kernel.core.transport.StreamLifecycle")
@Label("Stream Lifecycle Complete")
@Category({"Exeris Kernel", "Transport", "PAQS"})
@Description("Emitted when a stream's Virtual Thread completes (normal completion or handler exception).")
@StackTrace(false)
public final class StreamLifecycleEvent extends Event {

    /**
     * Normal completion — handler returned without throwing.
     */
    public static final String OUTCOME_COMPLETE = "COMPLETE";

    /**
     * Handler threw an exception — stream is closed, VT terminates.
     */
    public static final String OUTCOME_ERROR = "ERROR";

    /** SPI stream identifier, matching the {@code streamId} on the paired {@link StreamAcceptedEvent}. */
    @Label("Stream ID")
    public long streamId;

    /** {@link eu.exeris.kernel.spi.transport.StreamPriority} enum constant name the stream was admitted with. */
    @Label("Priority")
    public String priority;

    /** {@link #OUTCOME_COMPLETE} if the handler returned normally, {@link #OUTCOME_ERROR} if it threw. */
    @Label("Outcome")
    @Description("COMPLETE or ERROR")
    public String outcome;

    /**
     * Nanoseconds from the start of the stream handler's virtual-thread execution to this
     * event's emission — measured with {@code System.nanoTime()} inside
     * {@code PaqsScheduler.runStream()}; excludes any virtual-thread scheduling delay between
     * admission (see {@link StreamAcceptedEvent}) and the virtual thread actually starting.
     */
    @Label("Duration (ns)")
    @Description("Wall-clock nanos the stream's Virtual Thread was active")
    public long durationNs;

    /**
     * Factory: emits a stream lifecycle completion event.
     *
     * @param streamId   the SPI stream identifier
     * @param priority   the {@link eu.exeris.kernel.spi.transport.StreamPriority} name
     * @param outcome    {@link #OUTCOME_COMPLETE} or {@link #OUTCOME_ERROR}
     * @param durationNs total Virtual Thread active duration in nanoseconds
     */
    public static void emit(long streamId, String priority, String outcome, long durationNs) {
        if (!FlightRecorder.isInitialized()) {
            return;
        }
        StreamLifecycleEvent evt = new StreamLifecycleEvent();
        if (evt.isEnabled()) {
            evt.streamId = streamId;
            evt.priority = priority;
            evt.outcome = outcome;
            evt.durationNs = durationNs;
            evt.commit();
        }
    }
}
