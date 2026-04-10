/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.core.http.jfr;

import jdk.jfr.Category;
import jdk.jfr.Description;
import jdk.jfr.Event;
import jdk.jfr.FlightRecorder;
import jdk.jfr.Label;
import jdk.jfr.Name;
import jdk.jfr.StackTrace;

/**
 * JFR event emitted when HTTP aggregate buffer is forcibly released and re-allocated.
 *
 * <h2>Buffer Lifecycle Control</h2>
 * <p>Emitted when adaptive buffer release logic determines that pipelined request
 * fraction is low, forcing buffer release to reduce memory footprint.
 *
 * @since 0.5.0
 */
@Name("eu.exeris.kernel.core.http.AggregateBufferForcedRelease")
@Label("HTTP Aggregate Buffer Forced Release")
@Description("Emitted when HTTP codec aggregate buffer is forcibly released and re-allocated")
@Category({"Exeris Kernel", "HTTP", "Memory"})
@StackTrace(false)
public final class HttpAggregateBufferForcedReleaseEvent extends Event {

    /** Why buffer was forced to release. */
    @Label("Reason")
    public String reason;

    /** Buffered bytes at time of release. */
    @Label("Buffered Bytes at Release")
    public int bufferedBytes;

    /** Pipelined request fraction threshold that triggered release. */
    @Label("Threshold")
    public double threshold;

    /** Current pipelined request fraction. */
    @Label("Current Fraction")
    public double currentFraction;

    /**
     * Emit a forced release event.
     *
     * <p>Guards on {@link FlightRecorder#isInitialized()} to avoid allocation when JFR is off.
     */
    public static void emit(String reason, int bufferedBytes, double threshold, double currentFraction) {
        if (!FlightRecorder.isInitialized()) {
            return;
        }
        HttpAggregateBufferForcedReleaseEvent event = new HttpAggregateBufferForcedReleaseEvent();
        if (!event.isEnabled()) {
            return;
        }
        event.reason = reason;
        event.bufferedBytes = bufferedBytes;
        event.threshold = threshold;
        event.currentFraction = currentFraction;
        event.commit();
    }
}
