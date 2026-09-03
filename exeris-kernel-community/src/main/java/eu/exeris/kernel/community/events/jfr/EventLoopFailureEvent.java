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
 */
@Label("Event Loop Failure")
@Category({"Exeris", "Events", "Community"})
@StackTrace(false)
public final class EventLoopFailureEvent extends Event {

    @Label("Loop Name")
    public String loopName;

    /** One of: {@code "UNCAUGHT"}, {@code "DISPATCH"}, {@code "REQUEUE"}. */
    @Label("Failure Phase")
    public String phase;

    @Label("Exception Type")
    public String exceptionType;

    @Label("Affected Count")
    public int affectedCount;

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
