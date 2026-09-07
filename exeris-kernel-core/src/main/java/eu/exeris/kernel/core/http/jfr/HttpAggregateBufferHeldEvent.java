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
 * JFR event emitted when the HTTP/1.1 aggregate read buffer has been held, across one or more
 * keep-alive iterations on the same connection, longer than a single fixed age threshold.
 *
 * <h2>Buffer Lifecycle Tracking</h2>
 * <p>Tracks aggregate buffer age across request boundaries. Emitted, once per keep-alive
 * iteration, whenever that age exceeds {@code CommunityHttpAggregateTelemetry}'s single
 * 100&nbsp;ms warning threshold — the same threshold regardless of whether the connection's
 * traffic is pipelined.
 *
 * <p>Emitted from {@code CommunityHttpAggregateTelemetry.applyAndRelease}, called at the end of
 * every keep-alive iteration that is not being torn down.
 *
 * @since 0.5
 */
@Name("eu.exeris.kernel.core.http.AggregateBufferHeld")
@Label("HTTP Aggregate Buffer Held")
@Description("Emitted when HTTP codec aggregate buffer is held longer than expected")
@Category({"Exeris Kernel", "HTTP", "Memory"})
@StackTrace(false)
public final class HttpAggregateBufferHeldEvent extends Event {

    /**
     * Milliseconds elapsed since the current aggregate buffer instance was (re)allocated for this
     * connection — not since the connection or the buffer's last individual request.
     */
    @Label("Age (ms)")
    public long ageMs;

    /** Bytes buffered in the aggregate, carried over from the connection's last request, at the moment of emission. */
    @Label("Buffered Bytes")
    public int bufferedBytes;

    /**
     * The configured aggregate-buffer ceiling in bytes, as supplied by the caller — not this
     * buffer's actual current allocated capacity, which starts smaller and grows on demand up to
     * this ceiling.
     */
    @Label("Capacity")
    public int capacity;

    /**
     * Running fraction of this connection's requests that were pipelined (landed in a buffer that
     * already held bytes from a prior request), measured over the connection's lifetime so far. A
     * fraction in {@code [0,1]}, not a percentage.
     */
    @Label("Pipelined Fraction")
    public double pipelinedFraction;

    /**
     * Creates an unrecorded event.
     *
     * <p>{@link #emit} assigns the public fields and calls {@link Event#commit()}. An instance that is never
     * committed contributes nothing to a recording.
     */
    public HttpAggregateBufferHeldEvent() {
        // Declared, not added: the implicit no-arg constructor, written out so it can carry a comment.
        super();
    }

    /**
     * Emits a buffer-held event, unless JFR is disabled or the event itself is disabled.
     *
     * <p>Guards on {@link FlightRecorder#isInitialized()} to avoid allocation when JFR is off.
     *
     * @param ageMs             milliseconds since the aggregate buffer was (re)allocated
     * @param bufferedBytes     bytes currently buffered in the aggregate
     * @param capacity          the configured aggregate-buffer ceiling in bytes
     * @param pipelinedFraction running pipelined-request fraction, in {@code [0,1]}
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
