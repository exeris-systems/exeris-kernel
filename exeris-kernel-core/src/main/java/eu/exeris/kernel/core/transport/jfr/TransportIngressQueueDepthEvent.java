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
 * JFR event emitted when TLS ingress queue depth crosses monitoring thresholds.
 *
 * <h2>Memory Tracking</h2>
 * <p>Monitors per-stream ingress buffer queue to detect accumulation patterns.
 * Emitted at thresholds: 100, 500, 1000 retained buffers.
 * Peak depth should remain ≤200 under normal load.
 *
 * @since 0.5.0
 */
@Name("eu.exeris.kernel.core.transport.IngressQueueDepth")
@Label("Transport Ingress Queue Depth")
@Description("Emitted when TLS ingress queue depth crosses monitoring threshold")
@Category({"Exeris Kernel", "Transport", "Memory"})
@StackTrace(false)
public final class TransportIngressQueueDepthEvent extends Event {

    /** Stream ID being monitored. */
    @Label("Stream ID")
    public long streamId;

    /** Current queue depth (number of retained buffers). */
    @Label("Queue Depth")
    public int queueDepth;

    /** Threshold that triggered this event (100, 500, 1000, etc.). */
    @Label("Threshold")
    public int threshold;

    /** Whether queue depth is trending up or down. */
    @Label("Trend")
    public String trend;

    /**
     * Emit an ingress queue depth event.
     *
     * <p>Guards on {@link FlightRecorder#isInitialized()} to avoid allocation when JFR is off.
     */
    public static void emit(long streamId, int queueDepth, int threshold, String trend) {
        if (!FlightRecorder.isInitialized()) {
            return;
        }
        TransportIngressQueueDepthEvent event = new TransportIngressQueueDepthEvent();
        if (!event.isEnabled()) {
            return;
        }
        event.streamId = streamId;
        event.queueDepth = queueDepth;
        event.threshold = threshold;
        event.trend = trend;
        event.commit();
    }
}
