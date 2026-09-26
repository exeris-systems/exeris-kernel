/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.http;

import jdk.jfr.Category;
import jdk.jfr.Description;
import jdk.jfr.Event;
import jdk.jfr.FlightRecorder;
import jdk.jfr.Label;
import jdk.jfr.Name;
import jdk.jfr.StackTrace;

/**
 * JFR event marking a Community HTTP server lifecycle transition (e.g. bind, start, stop) on a
 * given port.
 */
@Name("eu.exeris.kernel.http.CommunityHttpLifecycle")
@Label("Community HTTP Lifecycle")
@Description("Community HTTP server lifecycle event")
@Category({"Exeris Kernel", "HTTP"})
@StackTrace(false)
final class CommunityHttpLifecycleEvent extends Event {

    /** The lifecycle transition this event marks, as a short caller-supplied token. */
    @Label("Action")
    /* default */ String action;

    /** The TCP port the server engine this event describes is bound to. */
    @Label("Port")
    /* default */ int port;

    /**
     * Emits a lifecycle event for {@code action} on {@code port}, or does nothing when Flight
     * Recorder is not initialized or this event type is disabled.
     *
     * @param action the lifecycle transition being recorded
     * @param port   the port the server engine is bound to
     */
    /* default */ static void emit(String action, int port) {
        if (!FlightRecorder.isInitialized()) {
            return;
        }
        CommunityHttpLifecycleEvent event = new CommunityHttpLifecycleEvent();
        if (!event.isEnabled()) {
            return;
        }
        event.action = action;
        event.port = port;
        event.commit();
    }
}