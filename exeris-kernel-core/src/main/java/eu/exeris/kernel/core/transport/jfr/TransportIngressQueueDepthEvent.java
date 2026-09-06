/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
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
 * JFR event emitted by {@code NativeTcpStream.offerIngress(LoanedBuffer)} when a stream's
 * inbound queue depth is at or above a monitoring threshold — for plaintext and TLS ingress
 * alike, since both feed the same per-stream inbound queue.
 *
 * <h2>Memory Tracking</h2>
 * <p>Level-triggered, not edge-triggered: this fires on every {@code offerIngress} call while
 * the depth stays at or above the lowest threshold (100), not only on the call that first
 * crosses one. Exactly one event fires per call, at the highest of the three thresholds (100,
 * 500, 1000 retained buffers) the current depth has reached.
 *
 * @since 0.5
 */
@Name("eu.exeris.kernel.core.transport.IngressQueueDepth")
@Label("Transport Ingress Queue Depth")
@Description("Emitted when TLS ingress queue depth crosses monitoring threshold")
@Category({"Exeris Kernel", "Transport", "Memory"})
@StackTrace(false)
public final class TransportIngressQueueDepthEvent extends Event {

    /** SPI stream identifier of the stream whose ingress queue this event reports on. */
    @Label("Stream ID")
    public long streamId;

    /**
     * Number of buffers already retained in the stream's inbound queue at the moment this
     * buffer is offered — the backlog ahead of the current buffer, not the depth including it.
     */
    @Label("Queue Depth")
    public int queueDepth;

    /**
     * The exact threshold value that was met to trigger this event: 100, 500 or 1000 retained
     * buffers ({@code QUEUE_DEPTH_THRESHOLD_LOW}/{@code MID}/{@code HIGH} in the Community
     * carrier). Never any other value.
     */
    @Label("Threshold")
    public int threshold;

    /**
     * Literal {@code "up"} when this stream's queue depth is greater than or equal to its
     * previous recorded depth, {@code "down"} otherwise; no other value is produced.
     */
    @Label("Trend")
    public String trend;

    /**
     * Emits an ingress queue depth event.
     *
     * <p>Guards on {@link FlightRecorder#isInitialized()} to avoid allocation when JFR is off.
     *
     * @param streamId   the SPI stream identifier of the stream being monitored
     * @param queueDepth the backlog depth observed before this call's buffer is reserved
     * @param threshold  the threshold value that was met (100, 500 or 1000)
     * @param trend      {@code "up"} or {@code "down"}, per the field's contract
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
