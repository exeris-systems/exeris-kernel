/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.spi.memory;

/**
 * Leak detection strictness levels for the {@link MemoryAllocator}.
 *
 * <h2>Valhalla Readiness</h2>
 * <p>Enum constants are JVM singletons — no heap allocation on comparison.
 *
 * @see MemoryProviderConfig#leakDetection()
 * @since 0.5.0
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
