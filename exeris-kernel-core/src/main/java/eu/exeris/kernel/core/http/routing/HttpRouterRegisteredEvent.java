/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.core.http.routing;

import jdk.jfr.Category;
import jdk.jfr.Event;
import jdk.jfr.Label;
import jdk.jfr.StackTrace;

@Category({"Exeris", "HTTP"})
@Label("HTTP Router Registered")
@StackTrace(false)
final class HttpRouterRegisteredEvent extends Event {

    // Package-private for same-package router tests and diagnostics.
    /* default */ int exactRouteCount;
    // Package-private for same-package router tests and diagnostics.
    /* default */ int prefixRouteCount;

    // Package-private for same-package router lifecycle emission only.
    /* default */ static void emit(int exactCount, int prefixCount) {
        HttpRouterRegisteredEvent event = new HttpRouterRegisteredEvent();
        if (!event.isEnabled()) {
            return;
        }
        event.exactRouteCount = exactCount;
        event.prefixRouteCount = prefixCount;
        event.commit();
    }
}
