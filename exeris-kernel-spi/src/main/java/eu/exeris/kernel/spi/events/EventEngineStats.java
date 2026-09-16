/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.spi.events;

/**
 * Immutable snapshot of {@link EventEngine} runtime statistics.
 *
 * <h2>JFR-First</h2>
 * <p>This record is the data carrier for the {@code EventEngineStatsEvent} JFR event.
 * All fields are primitives — low-allocation snapshot (zero-allocation ready for
 * {@code value record} migration once JEP 401 is mainline).
 *
 * <h2>Valhalla Readiness</h2>
 * <p>All fields are primitives. No identity operations. Ready for {@code value record} migration.
 *
 * @param publishedTotal    total number of events published since engine start
 * @param processedTotal    total number of events processed (dispatched to handlers)
 * @param failedTotal       total number of events that caused handler errors
 * @param queueDepth        current number of events waiting in the queue
 * @param queueCapacity     maximum queue capacity
 * @param avgDispatchNanos  exponentially-weighted average dispatch latency in nanoseconds
 * @param loopRunning       {@code true} if the event loop is currently running
 *
 * @since 0.5
 * @see EventEngine#stats()
 */
public record EventEngineStats(
        long publishedTotal,
        long processedTotal,
        long failedTotal,
        long queueDepth,
        long queueCapacity,
        long avgDispatchNanos,
        boolean loopRunning
) {

    // CHECKSTYLE.OFF: DeclarationOrder — static constants in records must follow components list

    /** Zero-state snapshot (useful for pre-start or test contexts). */
    public static final EventEngineStats ZERO =
            new EventEngineStats(0L, 0L, 0L, 0L, 0L, 0L, false);

    /** Sentinel for an empty queue capacity — avoids PMD AvoidLiteralsInIfCondition. */
    private static final long EMPTY_CAPACITY = 0L;

    /** Sentinel for zero processed events — avoids PMD AvoidLiteralsInIfCondition. */
    private static final long EMPTY_PROCESSED = 0L;

    // CHECKSTYLE.ON: DeclarationOrder

    /**
     * Expresses queue pressure as a fraction of capacity — the number an operator alerts on when
     * back-pressure starts to bite.
     *
     * @return {@code queueDepth / queueCapacity}, in the range [0.0, 1.0]; {@code 0.0} when
     *         {@code queueCapacity} is zero, so an unconfigured queue reads as idle rather than
     *         dividing by zero
     */
    public double queueUtilization() {
        if (queueCapacity <= EMPTY_CAPACITY) {
            return 0.0;
        }
        return (double) queueDepth / (double) queueCapacity;
    }

    /**
     * Expresses handler failures as a fraction of everything the engine has dispatched since
     * start — a lifetime ratio, not a windowed rate.
     *
     * @return {@code failedTotal / processedTotal}, in the range [0.0, 1.0]; {@code 0.0} when
     *         nothing has been processed yet
     */
    public double errorRate() {
        if (processedTotal <= EMPTY_PROCESSED) {
            return 0.0;
        }
        return (double) failedTotal / (double) processedTotal;
    }
}

