/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.websocket;

import jdk.jfr.Category;
import jdk.jfr.Description;
import jdk.jfr.Event;
import jdk.jfr.Label;
import jdk.jfr.Name;
import jdk.jfr.StackTrace;

/**
 * Glass-Box: the WebSocket endpoint bound, or stopped.
 *
 * <p>Single-phase commit, never {@code begin()} then a blocking call then {@code commit()} — a
 * carrier-bound {@code EventWriter} straddling a virtual-thread unmount has crashed the JVM before.
 */
@Name("eu.exeris.kernel.websocket.Lifecycle")
@Label("WebSocket Lifecycle")
@Category({"Exeris", "WebSocket"})
@Description("A WebSocket server engine started or stopped")
@StackTrace(false)
final class CommunityWebSocketLifecycleEvent extends Event {

    @Label("Phase")
    /* default */ String phase;

    @Label("Port")
    /* default */ int port;

    /* default */ static void emit(String phase, int port) {
        CommunityWebSocketLifecycleEvent event = new CommunityWebSocketLifecycleEvent();
        if (event.isEnabled()) {
            event.phase = phase;
            event.port = port;
            event.commit();
        }
    }
}
