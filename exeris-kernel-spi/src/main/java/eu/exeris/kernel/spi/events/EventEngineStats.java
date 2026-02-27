/*
 * Copyright (C) 2025-2026 Exeris. All rights reserved.
 *
 * This code is part of the Exeris Platform.
 * Distributed under the proprietary Exeris Software License.
 * Unauthorized copying or distribution is prohibited.
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
 * @since 0.5.0
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
     * Returns the percentage of queue capacity currently used.
     *
     * @return value in range [0.0, 1.0]
     */
    public double queueUtilisation() {
        if (queueCapacity <= EMPTY_CAPACITY) {
            return 0.0;
        }
        return (double) queueDepth / (double) queueCapacity;
    }

    /**
     * Returns the error rate as a fraction of total processed events.
     *
     * @return value in range [0.0, 1.0]
     */
    public double errorRate() {
        if (processedTotal <= EMPTY_PROCESSED) {
            return 0.0;
        }
        return (double) failedTotal / (double) processedTotal;
    }
}

