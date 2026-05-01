/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
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
 * JFR event emitted when a PAQS stream handler fails inside the VT boundary.
 *
 * @since 0.5.0
 */
@Name("eu.exeris.kernel.core.transport.PaqsHandlerFailure")
@Label("PAQS Handler Failure")
@Category({"Exeris Kernel", "Transport", "PAQS"})
@Description("Emitted when a PAQS stream handler fails during stream lifecycle processing.")
@StackTrace(false)
public final class PaqsHandlerFailureEvent extends Event {

    @Label("Stream ID")
    public long streamId;

    @Label("Engine Name")
    public String engineName;

    @Label("Exception Class")
    public String exceptionClass;

    @Label("Lifecycle Phase")
    public String lifecyclePhase;

    public static void emit(long streamId,
                            String engineName,
                            String exceptionClass,
                            String lifecyclePhase) {
        if (!FlightRecorder.isInitialized()) {
            return;
        }
        PaqsHandlerFailureEvent evt = new PaqsHandlerFailureEvent();
        if (evt.isEnabled()) {
            evt.streamId = streamId;
            evt.engineName = engineName;
            evt.exceptionClass = exceptionClass;
            evt.lifecyclePhase = lifecyclePhase;
            evt.commit();
        }
    }
}