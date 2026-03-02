/*
 * Copyright (C) 2025-2026 Exeris. All rights reserved.
 *
 * This code is part of the Exeris Systems.
 * Distributed under the proprietary Exeris Software License.
 * Unauthorized copying or distribution is prohibited.
 */
package eu.exeris.kernel.spi.persistence;

/**
 * SPI: Immutable health status record returned by {@link PersistenceEngine#healthCheckDetailed()}.
 *
 * <h2>Replaces {@code boolean healthCheck()}</h2>
 * <p>The original {@code boolean} return value was insufficient for operator tooling —
 * it provided no latency information and no reason for failure. This record adds:
 * <ul>
 *   <li>Structured failure reason (propagated to JFR health events)</li>
 *   <li>Round-trip latency in nanoseconds (for SLO monitoring)</li>
 *   <li>Zero-allocation factory methods — both constants and inline creation</li>
 * </ul>
 *
 * <h2>Valhalla Readiness</h2>
 * <p>This is a standard Java {@code record}. No identity operations are used
 * ({@code ==}, {@code synchronized}, {@code System.identityHashCode()}).
 * C2 JIT escape analysis will scalarise instances on the hot path.
 * Will be migrated to {@code value record} once JEP 401 is mainline.
 *
 * <h2>Pre-allocated Constants</h2>
 * <p>Use {@link #UNKNOWN} and {@link #DEGRADED_LATENCY} as sentinel values.
 * For healthy checks, always allocate with {@link #ok(long)} — one record per
 * health-check invocation (cold path, not hot path).
 *
 * @since 0.5.0
 * @see PersistenceEngine
 */
public record PersistenceHealthStatus(
        boolean healthy,
        String  message,
        long    latencyNanos
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
     * Creates a failed status with a reason extracted from an exception.
     *
     * @param cause the exception that caused the failure
     * @return an unhealthy {@link PersistenceHealthStatus}
     */
    public static PersistenceHealthStatus failed(Throwable cause) {
        String reason = cause != null
                ? cause.getClass().getSimpleName() + ": " + cause.getMessage()
                : UNKNOWN_FAILURE;
        return new PersistenceHealthStatus(false, reason, -1L);
    }

    /**
     * Returns {@code true} if the latency exceeds the standard 100 ms SLO threshold.
     *
     * @return {@code true} if degraded
     */
    public boolean isDegradedLatency() {
        return latencyNanos > 100_000_000L; // 100 ms in nanoseconds
    }

    @Override
    public String toString() {
        return "PersistenceHealthStatus[healthy=" + healthy
                + ", latencyNanos=" + latencyNanos
                + ", message=" + message + "]";
    }
}

