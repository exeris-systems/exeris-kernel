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

/**
 * JFR event emitted when an HTTP/2 connection trips the Rapid Reset flood defense
 * (CVE-2023-44487, {@code EX-HTTP-4010}): a peer opened-then-reset streams past the
 * per-connection rapid-reset budget without matching work, so the connection is terminated
 * with {@code GOAWAY(ENHANCE_YOUR_CALM)}.
 *
 * <p><b>Secret-safe.</b> Carries only connection-scoped counts — the net inbound reset count
 * at trip time and the last processed stream id — never request headers, bodies, or peer
 * identity. Single-phase commit ({@code @StackTrace(false)}): construct, set, commit with no
 * blocking operation in between, so it is safe to emit from a virtual carrier thread.
 */
@Name("eu.exeris.kernel.http.Http2RapidResetFlood")
@Label("HTTP/2 Rapid Reset Flood")
@Description("HTTP/2 Rapid Reset (CVE-2023-44487) flood defense tripped; connection terminated")
@Category({"Exeris Kernel", "HTTP"})
@StackTrace(false)
final class Http2RapidResetFloodEvent extends Event {

    @Label("Reset Count")
    /* default */ int resetCount;

    @Label("Last Processed Stream Id")
    /* default */ int lastProcessedStreamId;

    /* default */ static void emit(int resetCount, int lastProcessedStreamId) {
        if (!FlightRecorder.isInitialized()) {
            return;
        }
        Http2RapidResetFloodEvent event = new Http2RapidResetFloodEvent();
        if (!event.isEnabled()) {
            return;
        }
        event.resetCount = resetCount;
        event.lastProcessedStreamId = lastProcessedStreamId;
        event.commit();
    }
}
