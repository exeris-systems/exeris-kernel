/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.core.http.routing;

import jdk.jfr.Category;
import jdk.jfr.Event;
import jdk.jfr.FlightRecorder;
import jdk.jfr.Label;
import jdk.jfr.StackTrace;

@Category({"Exeris", "HTTP"})
@Label("HTTP Router Registered")
@StackTrace(false)
final class HttpRouterRegisteredEvent extends Event {

    // Package-private for same-package router tests and diagnostics.
    /* default */ int exactRouteCount;
    // Package-private for same-package router tests and diagnostics.
    /* default */ int templateRouteCount;
    // Package-private for same-package router tests and diagnostics.
    /* default */ int prefixRouteCount;

    // Package-private for same-package router lifecycle emission only.
    /* default */ static void emit(int exactCount, int templateCount, int prefixCount) {
        if (!FlightRecorder.isInitialized()) {
            return;
        }
        HttpRouterRegisteredEvent event = new HttpRouterRegisteredEvent();
        if (!event.isEnabled()) {
            return;
        }
        event.exactRouteCount = exactCount;
        event.templateRouteCount = templateCount;
        event.prefixRouteCount = prefixCount;
        event.commit();
    }
}
