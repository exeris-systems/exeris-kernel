/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.events.jfr;

import jdk.jfr.Category;
import jdk.jfr.Event;
import jdk.jfr.FlightRecorder;
import jdk.jfr.Label;
import jdk.jfr.StackTrace;

/**
 * JFR event emitted when the community event loop encounters a failure during dispatch,
 * requeue, or as an uncaught exception on the virtual thread.
 *
 * @implNote {@link #emit} commits in a single phase — there is no {@link Event#begin()} call —
 *           so the event carries no measured duration, only a point-in-time occurrence; it
 *           therefore cannot straddle a blocking operation on the emitting thread. Unlike the
 *           other events in this package, {@link #emit} also checks
 *           {@link FlightRecorder#isInitialized()} before constructing the event at all.
 */
@Label("Event Loop Failure")
@Category({"Exeris", "Events", "Community"})
@StackTrace(false)
public final class EventLoopFailureEvent extends Event {

    /** Name of the virtual thread the loop was running on when the failure occurred. */
    @Label("Loop Name")
    public String loopName;

    /** One of: {@code "UNCAUGHT"}, {@code "DISPATCH"}, {@code "REQUEUE"}. */
    @Label("Failure Phase")
    public String phase;

    /** Simple class name of the triggering exception, or {@code "Unknown"} if none was given. */
    @Label("Exception Type")
    public String exceptionType;

    /** Number of events affected by the failure (e.g. the size of the failed dispatch batch). */
    @Label("Affected Count")
    public int affectedCount;

    /**
     * Commits this event, recording the loop name, failure phase, the triggering exception's
     * simple class name, and the number of events affected.
     *
     * <p>A no-op when {@link FlightRecorder} is not initialized or the event is disabled.
     *
     * @param loopName      name of the thread the loop was running on
     * @param phase         one of {@code "UNCAUGHT"}, {@code "DISPATCH"}, {@code "REQUEUE"}
     * @param failure       the triggering exception; {@code null} is recorded as {@code "Unknown"}
     * @param affectedCount number of events affected by the failure
     */
    public static void emit(String loopName, String phase, Throwable failure, int affectedCount) {
        if (!FlightRecorder.isInitialized()) {
            return;
        }
        EventLoopFailureEvent event = new EventLoopFailureEvent();
        if (!event.isEnabled()) {
            return;
        }
        event.loopName = loopName;
        event.phase = phase;
        event.exceptionType = failure != null ? failure.getClass().getSimpleName() : "Unknown";
        event.affectedCount = affectedCount;
        event.commit();
    }
}
