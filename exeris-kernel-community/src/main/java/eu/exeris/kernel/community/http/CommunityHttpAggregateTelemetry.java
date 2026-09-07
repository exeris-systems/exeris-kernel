/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.http;

import eu.exeris.kernel.core.http.jfr.HttpAggregateBufferForcedReleaseEvent;
import eu.exeris.kernel.core.http.jfr.HttpAggregateBufferHeldEvent;

/**
 * Community-internal HTTP/1.1 aggregate-buffer telemetry helper. Extracted from
 * {@link CommunityHttpRequestProcessor} in QA-011 (v0.8 Sprint 1) so the request
 * processor carries one less responsibility: the *"how long has this aggregate buffer
 * been held, what fraction of requests on it were actually pipelined, and should we
 * force-release it back to the allocator?"* decision tree lives here.
 *
 * <h2>Why these heuristics</h2>
 * <p>HTTP/1.1 keep-alive connections may host multiple requests in sequence. The
 * processor pre-allocates an aggregate read buffer when the first request arrives and
 * keeps it across the keep-alive loop so the second/third pipelined request can land
 * in the same buffer without an extra allocation. The cost: a long-lived keep-alive
 * connection with low pipelined traffic holds the buffer indefinitely.
 *
 * <p>Phase 1C (v0.6 telemetry) added two JFR signals:
 * <ul>
 *   <li>{@link HttpAggregateBufferHeldEvent} — emitted once a buffer has been held for
 *       more than {@value #BUFFER_AGE_WARNING_THRESHOLD_MS} ms, carries the buffered
 *       byte count and the running pipelined fraction.</li>
 *   <li>{@link HttpAggregateBufferForcedReleaseEvent} — emitted when the heuristic
 *       decides to release the buffer back to the allocator: pipelined fraction
 *       dropped below {@value #PIPELINED_FRACTION_THRESHOLD} (5%) AND the buffer is
 *       currently idle (zero buffered bytes from the last keep-alive iteration).</li>
 * </ul>
 *
 * <h2>Statelessness</h2>
 * <p>All methods are {@code static} — the helper reads counters from the supplied
 * {@link ProcessingState} and emits events. No internal state is retained between
 * invocations; per-connection state lives in {@code ProcessingState}.
 *
 * @since 0.8
 */
final class CommunityHttpAggregateTelemetry {

    /** Emit a "buffer held too long" JFR signal once the buffer age exceeds this. */
    private static final long BUFFER_AGE_WARNING_THRESHOLD_MS = 100;

    /** Force-release threshold: if fewer than 5% of requests were pipelined, drop the buffer. */
    private static final double PIPELINED_FRACTION_THRESHOLD = 0.05;

    private CommunityHttpAggregateTelemetry() {
        // utility — no instances
    }

    /**
     * Inspects the aggregate buffer state, emits the "held too long" JFR signal when
     * the age threshold is crossed, and force-releases the buffer (also emitting a
     * forced-release JFR signal) when the pipelined fraction has dropped low enough
     * to justify the de-allocation. No-op when the buffer is fresh.
     *
     * @param state               the per-connection processing state
     * @param maxAggregateBytes   the configured aggregate ceiling — emitted in the
     *                            "held" event so dashboards can show the held fraction
     */
    /* default */ static void applyAndRelease(ProcessingState state, int maxAggregateBytes) {
        long aggregateAgeMs = aggregateAgeMillis(state.aggregateAllocationTimeNs());
        if (!shouldEmitAggregateHeldTelemetry(aggregateAgeMs)) {
            return;
        }

        double pipelinedFraction = pipelinedFraction(state.totalRequestCount(), state.pipelinedRequestCount());
        emitAggregateHeldTelemetry(aggregateAgeMs, state.bufferedBytes(), pipelinedFraction, maxAggregateBytes);
        if (shouldForceReleaseAggregate(pipelinedFraction, state.bufferedBytes())) {
            emitForcedReleaseTelemetry(state.bufferedBytes(), pipelinedFraction);
            state.forceReleaseAggregate();
        }
    }

    private static long aggregateAgeMillis(long aggregateAllocationTimeNs) {
        return (System.nanoTime() - aggregateAllocationTimeNs) / 1_000_000;
    }

    private static boolean shouldEmitAggregateHeldTelemetry(long aggregateAgeMs) {
        return aggregateAgeMs > BUFFER_AGE_WARNING_THRESHOLD_MS;
    }

    private static double pipelinedFraction(long totalRequestCount, long pipelinedRequestCount) {
        return totalRequestCount > 0
                ? (double) pipelinedRequestCount / totalRequestCount
                : 0.0;
    }

    private static void emitAggregateHeldTelemetry(long aggregateAgeMs,
                                                   long bufferedBytes,
                                                   double pipelinedFraction,
                                                   int maxAggregateBytes) {
        HttpAggregateBufferHeldEvent.emit(
                aggregateAgeMs,
                (int) bufferedBytes,
                maxAggregateBytes,
                pipelinedFraction
        );
    }

    private static boolean shouldForceReleaseAggregate(double pipelinedFraction, long bufferedBytes) {
        return pipelinedFraction < PIPELINED_FRACTION_THRESHOLD && bufferedBytes == 0;
    }

    private static void emitForcedReleaseTelemetry(long bufferedBytes, double pipelinedFraction) {
        HttpAggregateBufferForcedReleaseEvent.emit(
                "low_pipelined_fraction",
                (int) bufferedBytes,
                PIPELINED_FRACTION_THRESHOLD,
                pipelinedFraction
        );
    }
}
