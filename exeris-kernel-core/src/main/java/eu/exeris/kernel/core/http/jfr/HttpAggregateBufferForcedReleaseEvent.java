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
 * JFR event emitted when the HTTP/1.1 aggregate read buffer is forcibly released back to the
 * allocator ahead of its normal keep-alive-connection-close reclamation.
 *
 * <h2>Buffer Lifecycle Control</h2>
 * <p>Emitted when adaptive buffer release logic determines that pipelined request
 * fraction is low, forcing buffer release to reduce memory footprint. Releasing does not
 * re-allocate: a later request on the same connection allocates a fresh aggregate buffer lazily,
 * as it would for a connection that never held one.
 *
 * <p>Emitted from {@code CommunityHttpAggregateTelemetry.applyAndRelease}, which force-releases
 * only when the buffer is already idle (zero bytes buffered from the prior keep-alive iteration)
 * — see {@link #bufferedBytes}.
 *
 * @since 0.5
 */
@Name("eu.exeris.kernel.core.http.AggregateBufferForcedRelease")
@Label("HTTP Aggregate Buffer Forced Release")
@Description("Emitted when HTTP codec aggregate buffer is forcibly released and re-allocated")
@Category({"Exeris Kernel", "HTTP", "Memory"})
@StackTrace(false)
public final class HttpAggregateBufferForcedReleaseEvent extends Event {

    /**
     * Why the buffer was force-released. The only current caller always passes the literal
     * {@code "low_pipelined_fraction"}.
     */
    @Label("Reason")
    public String reason;

    /**
     * Bytes buffered in the aggregate at the moment of release. The only current caller
     * force-releases exclusively when the buffer is already idle, so this value is currently
     * always {@code 0}; it is not a general upper bound on what could be lost by the release.
     */
    @Label("Buffered Bytes at Release")
    public int bufferedBytes;

    /**
     * The pipelined-request-fraction threshold that, once the current fraction drops below it,
     * triggers a force-release. A fraction in {@code [0,1]}, not a percentage.
     */
    @Label("Threshold")
    public double threshold;

    /**
     * The running pipelined-request fraction that triggered this release, measured at the moment
     * of emission. A fraction in {@code [0,1]}, not a percentage; always less than
     * {@link #threshold} for this event to have fired.
     */
    @Label("Current Fraction")
    public double currentFraction;

    /**
     * Creates an unrecorded event.
     *
     * <p>{@link #emit} assigns the public fields and calls {@link Event#commit()}. An instance that is never
     * committed contributes nothing to a recording.
     */
    public HttpAggregateBufferForcedReleaseEvent() {
        // Declared, not added: the implicit no-arg constructor, written out so it can carry a comment.
        super();
    }

    /**
     * Emits a forced-release event, unless JFR is disabled or the event itself is disabled.
     *
     * <p>Guards on {@link FlightRecorder#isInitialized()} to avoid allocation when JFR is off.
     *
     * @param reason          why the buffer was force-released
     * @param bufferedBytes   bytes buffered in the aggregate at the moment of release
     * @param threshold       the pipelined-fraction threshold that triggered the release, in
     *                        {@code [0,1]}
     * @param currentFraction the running pipelined fraction that triggered the release, in
     *                        {@code [0,1]}
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
