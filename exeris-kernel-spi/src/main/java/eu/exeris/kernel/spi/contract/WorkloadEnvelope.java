/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.spi.contract;

/**
 * Immutable workload parameters defining throughput and connection quotas per ADR-088 / ADR-089.
 *
 * @param maxThroughputRps       maximum permitted requests per second, or {@link Long#MAX_VALUE} if unconstrained
 * @param maxConnections         maximum concurrent connections, or {@link Integer#MAX_VALUE} if unconstrained
 * @param growthAllowancePercent percentage margin permitted beyond nominal envelope before AUDIT alerting
 * @since 0.13
 */
public record WorkloadEnvelope(
        long maxThroughputRps,
        int maxConnections,
        int growthAllowancePercent
) {

    /** Default unconstrained workload envelope for evaluation and unbounded tiers. */
    public static final WorkloadEnvelope UNLIMITED =
            new WorkloadEnvelope(Long.MAX_VALUE, Integer.MAX_VALUE, 0);

    /**
     * Compact constructor validating that numeric thresholds are non-negative.
     */
    public WorkloadEnvelope {
        if (maxThroughputRps < 0) {
            throw new IllegalArgumentException("maxThroughputRps must be non-negative");
        }
        if (maxConnections < 0) {
            throw new IllegalArgumentException("maxConnections must be non-negative");
        }
        if (growthAllowancePercent < 0) {
            throw new IllegalArgumentException("growthAllowancePercent must be non-negative");
        }
    }
}
