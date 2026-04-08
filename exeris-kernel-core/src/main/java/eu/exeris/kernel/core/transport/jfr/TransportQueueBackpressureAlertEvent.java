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
 * JFR event emitted when TLS ingress queue backpressure circuit breaker activates.
 *
 * <h2>Backpressure Control</h2>
 * <p>Only emitted when circuit breaker is enabled (flag: -Deris.transport.queueBackpressureEnabled=true).
 * Indicates that queue depth exceeded threshold and new connection accept was rejected.
 *
 * @since 0.6.0
 */
@Name("eu.exeris.kernel.core.transport.QueueBackpressureAlert")
@Label("Transport Queue Backpressure Alert")
@Description("Emitted when TLS ingress queue backpressure circuit breaker activates")
@Category({"Exeris Kernel", "Transport", "Backpressure"})
@StackTrace(false)
public final class TransportQueueBackpressureAlertEvent extends Event {

    /** Number of connections rejected due to backpressure. */
    @Label("Connections Rejected")
    public int connectionsRejected;

    /** Peak queue depth that triggered backpressure. */
    @Label("Peak Queue Depth")
    public int peakQueueDepth;

    /** Trend indicator (whether queue is still growing). */
    @Label("Trend")
    public String trend;

    /**
     * Emit a backpressure alert event.
     *
     * <p>Guards on {@link FlightRecorder#isInitialized()} to avoid allocation when JFR is off.
     */
    public static void emit(int connectionsRejected, int peakQueueDepth, String trend) {
        if (!FlightRecorder.isInitialized()) {
            return;
        }
        TransportQueueBackpressureAlertEvent event = new TransportQueueBackpressureAlertEvent();
        event.connectionsRejected = connectionsRejected;
        event.peakQueueDepth = peakQueueDepth;
        event.trend = trend;
        event.commit();
    }
}
