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
 * JFR event emitted by {@code NativeTcpStream.offerIngress(LoanedBuffer)} when the per-stream
 * inbound queue's backpressure gate rejects a buffer — reached from plaintext and TLS ingress
 * alike, since both feed the same inbound queue.
 *
 * <h2>Backpressure Control</h2>
 * <p>Only emitted when the circuit breaker is enabled
 * ({@code -Dexeris.transport.queueBackpressureEnabled=true}, default disabled). The rejected
 * buffer is closed and an {@code IllegalStateException} is thrown out of {@code offerIngress};
 * on the reactor dispatch path that is not a return to any caller but a fault — the reactor's
 * key-dispatch loop catches it, closes the stream, and separately emits a
 * {@code CommunityReactorDispatchFaultEvent}. This event is therefore the leading signal of a
 * stream about to be closed, not a standalone incident.
 *
 * @since 0.5
 */
@Name("eu.exeris.kernel.core.transport.QueueBackpressureAlert")
@Label("Transport Queue Backpressure Alert")
@Description("Emitted when TLS ingress queue backpressure circuit breaker activates")
@Category({"Exeris Kernel", "Transport", "Backpressure"})
@StackTrace(false)
public final class TransportQueueBackpressureAlertEvent extends Event {

    /**
     * Always {@code 1} at every current call site: this event reports one rejected
     * inbound-buffer enqueue on one stream, not an aggregate count of connections despite the
     * field's label — no code path in this repository passes a different value.
     */
    @Label("Connections Rejected")
    public int connectionsRejected;

    /**
     * The stream's soft inbound-queue depth counter, read after it is incremented for the
     * rejected buffer — a single sample taken at this one rejection, not a running maximum
     * tracked across calls despite "Peak" in the field's label.
     */
    @Label("Peak Queue Depth")
    public int peakQueueDepth;

    /**
     * Literal {@code "up"} when the stream's queue depth was greater than or equal to its
     * previous recorded depth, {@code "down"} otherwise; no other value is produced.
     */
    @Label("Trend")
    public String trend;

    /**
     * Creates an unrecorded event.
     *
     * <p>{@link #emit} assigns the public fields and calls {@link Event#commit()}. An instance that is never
     * committed contributes nothing to a recording.
     */
    public TransportQueueBackpressureAlertEvent() {
        // Declared, not added: the implicit no-arg constructor, written out so it can carry a comment.
        super();
    }

    /**
     * Emits a backpressure alert event.
     *
     * <p>Guards on {@link FlightRecorder#isInitialized()} to avoid allocation when JFR is off.
     *
     * @param connectionsRejected number of rejections this event reports (currently always {@code 1})
     * @param peakQueueDepth      the queue-depth sample taken at the moment of this rejection
     * @param trend               {@code "up"} or {@code "down"}, per the field's contract
     */
    public static void emit(int connectionsRejected, int peakQueueDepth, String trend) {
        if (!FlightRecorder.isInitialized()) {
            return;
        }
        TransportQueueBackpressureAlertEvent event = new TransportQueueBackpressureAlertEvent();
        if (!event.isEnabled()) {
            return;
        }
        event.connectionsRejected = connectionsRejected;
        event.peakQueueDepth = peakQueueDepth;
        event.trend = trend;
        event.commit();
    }
}
