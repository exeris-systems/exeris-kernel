/*
 * Copyright (C) 2025-2026 Exeris. All rights reserved.
 *
 * This code is part of the Exeris Systems.
 * Distributed under the proprietary Exeris Software License.
 * Unauthorized copying or distribution is prohibited.
 */
package eu.exeris.kernel.spi.memory;

/**
 * Leak detection strictness levels for the {@link MemoryAllocator}.
 *
 * <h2>Valhalla Readiness</h2>
 * <p>Enum constants are JVM singletons — no heap allocation on comparison.
 *
 * @since 0.5.0
 * @see MemoryProviderConfig#leakDetection()
 */
public enum LeakDetectionMode {

    /**
     * No leak detection.
     * <p>Use in production. Zero allocation overhead on buffer creation/release paths.
     */
    DISABLED,

    /**
     * Sampling-based detection: ~1% of allocations are tracked via phantom references.
     * <p>Low overhead; suitable for canary deployments. Reports leaks to JFR asynchronously.
     */
    SAMPLED,

    /**
     * Full detection: every allocation is tracked.
     * <p>High overhead (~2× allocation cost). Use only in integration tests or
     * when hunting a specific leak in a controlled environment.
     */
    PARANOID
}

