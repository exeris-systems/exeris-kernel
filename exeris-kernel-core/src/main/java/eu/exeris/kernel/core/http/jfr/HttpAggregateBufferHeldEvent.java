/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
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
 * JFR event emitted when HTTP aggregate buffer is held longer than expected.
 *
 * <h2>Buffer Lifecycle Tracking</h2>
 * <p>Tracks aggregate buffer age across request boundaries.
 * Age should be &lt;10ms for non-pipelined requests, &lt;100ms for pipelined.
 * Emitted when age exceeds thresholds to detect buffer retention issues.
 *
 * @since 0.5
 */
@Name("eu.exeris.kernel.core.http.AggregateBufferHeld")
@Label("HTTP Aggregate Buffer Held")
@Description("Emitted when HTTP codec aggregate buffer is held longer than expected")
@Category({"Exeris Kernel", "HTTP", "Memory"})
@StackTrace(false)
public final class HttpAggregateBufferHeldEvent extends Event {

    /** Age of buffer (ms) since allocation or last retained. */
    @Label("Age (ms)")
    public long ageMs;

    /** Buffered bytes at time of event. */
    @Label("Buffered Bytes")
    public int bufferedBytes;

    /** Capacity of aggregate buffer. */
    @Label("Capacity")
    public int capacity;

    /** Current pipelined request fraction (moving average). */
    @Label("Pipelined Fraction")
    public double pipelinedFraction;

    /**
     * Emit a buffer held event.
     *
     * <p>Guards on {@link FlightRecorder#isInitialized()} to avoid allocation when JFR is off.
     */
    public static void emit(long ageMs, int bufferedBytes, int capacity, double pipelinedFraction) {
        if (!FlightRecorder.isInitialized()) {
            return;
        }
        HttpAggregateBufferHeldEvent event = new HttpAggregateBufferHeldEvent();
        if (!event.isEnabled()) {
            return;
        }
        event.ageMs = ageMs;
        event.bufferedBytes = bufferedBytes;
        event.capacity = capacity;
        event.pipelinedFraction = pipelinedFraction;
        event.commit();
    }
}
