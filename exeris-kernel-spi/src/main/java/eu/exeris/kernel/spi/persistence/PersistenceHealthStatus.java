/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.spi.persistence;

/**
 * SPI: Immutable health status record returned by {@link PersistenceEngine#healthCheckDetailed()}.
 *
 * <h2>What operator tooling gets</h2>
 * <ul>
 *   <li>A liveness verdict</li>
 *   <li>A structured failure reason (propagated to JFR health events)</li>
 *   <li>Round-trip latency in nanoseconds (for SLO monitoring)</li>
 * </ul>
 *
 * <h2>Valhalla Readiness</h2>
 * <p>This is a standard Java {@code record}. No identity operations are used
 * ({@code ==}, {@code synchronized}, {@code System.identityHashCode()}),
 * so C2 escape analysis can scalarise instances, and the type is ready to migrate to a
 * {@code value record} once JEP 401 is mainline.
 *
 * @param healthy      {@code true} when the probe completed a round trip against the database;
 *                     {@code false} when it failed or has not run yet
 * @param message      the reason behind {@code healthy}, safe to surface on a health endpoint —
 *                     never a driver message, since those carry credentials, JDBC URLs and
 *                     filesystem paths. Normalised to {@code "OK"} or {@code "Unknown failure"}
 *                     when the caller passes {@code null}; never {@code null} on a constructed
 *                     instance
 * @param latencyNanos measured round-trip latency of the health-check query in nanoseconds, or
 *                     {@code -1} when no round trip completed
 * @apiNote Use {@link #UNKNOWN} and {@link #DEGRADED_LATENCY} as sentinels rather than
 *          constructing an equivalent; a successful probe allocates one record through
 *          {@link #ok(long)}, which is a cold path — a health endpoint or a monitoring task,
 *          not per request.
 * @since 0.5
 * @see PersistenceEngine
 */
public record PersistenceHealthStatus(
        boolean healthy,
        String message,
        long latencyNanos
) {
    /**
     * Pre-allocated sentinel for "status not yet determined" — emitted by
     * {@code PersistenceEngine} during bootstrap before the first health check.
     */
    public static final PersistenceHealthStatus UNKNOWN =
            new PersistenceHealthStatus(false, "Health check not yet performed", -1L);

    /**
     * Pre-allocated sentinel for degraded latency — emitted when the health
     * check succeeds but the round-trip exceeds 100 ms.
     */
    public static final PersistenceHealthStatus DEGRADED_LATENCY =
            new PersistenceHealthStatus(true, "WARNING: health-check latency > 100ms", 100_000_001L);

    // =========================================================================
    // Pre-allocated sentinel constants
    // =========================================================================

    private static final String UNKNOWN_FAILURE = "Unknown failure";


    // =========================================================================
    // Compact constructor (validation) — must precede static factory methods
    // =========================================================================

    /**
     * Normalises a {@code null} {@code message} so that a constructed status always carries a
     * reason: {@code "OK"} when {@code healthy}, {@code "Unknown failure"} otherwise.
     *
     * <p>No other component is validated — a negative {@code latencyNanos} is the documented
     * "no round trip completed" encoding rather than an error.
     */
    public PersistenceHealthStatus {
        if (message == null) {
            message = healthy ? "OK" : UNKNOWN_FAILURE;
        }
    }

    // =========================================================================
    // Factory methods (cold path — allocation permitted)
    // =========================================================================

    /**
     * Creates a healthy status with the measured round-trip latency.
     *
     * @param latencyNanos round-trip latency of the health-check query, in nanoseconds
     * @return a healthy {@link PersistenceHealthStatus}
     */
    @SuppressWarnings("PMD.ShortMethodName") // 'ok' is the canonical factory name — analogous to Optional.of()
    public static PersistenceHealthStatus ok(long latencyNanos) {
        return new PersistenceHealthStatus(true, "OK", latencyNanos);
    }

    /**
     * Creates a failed status with a descriptive reason.
     *
     * @param reason human-readable failure reason (propagated to JFR events)
     * @return an unhealthy {@link PersistenceHealthStatus}
     */
    public static PersistenceHealthStatus failed(String reason) {
        return new PersistenceHealthStatus(false, reason != null ? reason : UNKNOWN_FAILURE, -1L);
    }

    /**
     * Creates a failed status whose reason is the exception's <em>class name</em>.
     *
     * <p>The exception message is deliberately not read: a driver message routinely carries
     * credentials, JDBC URLs and filesystem paths, and this value reaches health endpoints.
     *
     * @param cause the exception that caused the failure; {@code null} yields
     *              {@code "Unknown failure"}
     * @return an unhealthy {@link PersistenceHealthStatus} with {@code latencyNanos == -1}
     */
    public static PersistenceHealthStatus failed(Throwable cause) {
        final String reason;
        if (cause == null) {
            reason = UNKNOWN_FAILURE;
        } else {
            // Deliberately avoid cause.getMessage() to prevent leaking credentials,
            // JDBC URLs, filesystem paths, or other environment details to health endpoints.
            final Class<?> exceptionClass = cause.getClass();
            final String simpleName = exceptionClass.getSimpleName();
            reason = simpleName.isEmpty()
                    ? exceptionClass.getName()
                    : simpleName;
        }
        return new PersistenceHealthStatus(false, reason, -1L);
    }

    /**
     * Reports whether the measured latency breaches the 100 ms SLO threshold.
     *
     * @return {@code true} when {@code latencyNanos} exceeds 100 ms; {@code false} for a faster
     *         probe and for the {@code -1} encoding of "no round trip completed"
     */
    public boolean isDegradedLatency() {
        return latencyNanos > 100_000_000L; // 100 ms in nanoseconds
    }

    /**
     * Renders the status with {@code latencyNanos} ahead of {@code message}, rather than in
     * record-component order.
     *
     * @return the three components as {@code healthy}, {@code latencyNanos}, {@code message}
     */
    @Override
    public String toString() {
        return "PersistenceHealthStatus[healthy=" + healthy
                + ", latencyNanos=" + latencyNanos
                + ", message=" + message + "]";
    }
}
