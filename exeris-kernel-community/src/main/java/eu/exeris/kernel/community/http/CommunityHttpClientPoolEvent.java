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
 * JFR event marking a Community HTTP client connection pool operation.
 *
 * @since 0.12
 */
@Name("eu.exeris.kernel.community.http.HttpClientPool")
@Label("Community HTTP Client Pool")
@Description("Community HTTP client connection pool operation")
@Category({"Exeris Kernel", "HTTP"})
@StackTrace(false)
final class CommunityHttpClientPoolEvent extends Event {

    @Label("Action")
    /* default */ String action;

    @Label("Authority")
    /* default */ String authority;

    @Label("Total Pooled Connections")
    /* default */ int totalPooled;

    /* default */ static void emit(String action, String authority, int totalPooled) {
        if (!FlightRecorder.isInitialized()) {
            return;
        }
        CommunityHttpClientPoolEvent event = new CommunityHttpClientPoolEvent();
        if (!event.isEnabled()) {
            return;
        }
        event.action = action;
        event.authority = authority;
        event.totalPooled = totalPooled;
        event.commit();
    }
}
