/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.community.http;

import jdk.jfr.Category;
import jdk.jfr.Description;
import jdk.jfr.Event;
import jdk.jfr.FlightRecorder;
import jdk.jfr.Label;
import jdk.jfr.Name;
import jdk.jfr.StackTrace;

@Name("eu.exeris.kernel.http.CommunityHttpLifecycle")
@Label("Community HTTP Lifecycle")
@Description("Community HTTP server lifecycle event")
@Category({"Exeris Kernel", "HTTP"})
@StackTrace(false)
final class CommunityHttpLifecycleEvent extends Event {

    @Label("Action")
    /* default */ String action;

    @Label("Port")
    /* default */ int port;

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