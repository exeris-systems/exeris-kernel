/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.community.events.jfr;

import jdk.jfr.Category;
import jdk.jfr.Event;
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
}
