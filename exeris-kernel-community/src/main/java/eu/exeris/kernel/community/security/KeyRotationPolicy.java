/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.community.security;

import java.time.Duration;
import java.util.Objects;

/**
 * Format-blind, immutable rotation policy for a verifying key set.
 *
 * <p>This carrier describes <em>how long</em> a retired generation of verification
 * material remains acceptable after a newer generation arrives ({@link #overlapWindow()}),
 * and the maximum age of the last successfully-loaded generation before a failed
 * refresh forces a deterministic deny ({@link #staleFetchBudget()}).
 *
 * <p>It is intentionally free of any wire-format, key-algorithm or transport vocabulary
 * (no JWKS / RSA / HTTP terms). It is slated for promotion to
 * {@code eu.exeris.kernel.spi.security.identity} in v0.9+ alongside the
 * {@code IdentityProvider} SPI (ADR-040), so it must remain format-blind.
 *
 * @param overlapWindow    how long a retired generation still verifies after a newer
 *                         generation is installed; must be non-null and non-negative
 * @param staleFetchBudget maximum age of the last good generation before a refresh
 *                         failure forces deny; must be non-null and non-negative
 * @since 0.9.0
 */
public record KeyRotationPolicy(Duration overlapWindow, Duration staleFetchBudget) {

    private static final Duration DEFAULT_OVERLAP_WINDOW = Duration.ofMinutes(10L);
    private static final Duration DEFAULT_STALE_FETCH_BUDGET = Duration.ofHours(1L);

    /**
     * Canonical constructor. Validates that both durations are non-null and non-negative
     * (fail-closed: a malformed policy must never silently widen acceptance).
     */
    public KeyRotationPolicy {
        Objects.requireNonNull(overlapWindow, "overlapWindow must not be null");
        Objects.requireNonNull(staleFetchBudget, "staleFetchBudget must not be null");
        if (overlapWindow.isNegative()) {
            throw new IllegalArgumentException("overlapWindow must not be negative");
        }
        if (staleFetchBudget.isNegative()) {
            throw new IllegalArgumentException("staleFetchBudget must not be negative");
        }
    }

    /**
     * Creates a policy from an explicit overlap window and stale-fetch budget.
     *
     * @param overlap   overlap window for retired generations
     * @param stale     stale-fetch budget for the current generation
     * @return a validated policy
     * @since 0.9.0
     */
    public static KeyRotationPolicy withWindows(Duration overlap, Duration stale) {
        return new KeyRotationPolicy(overlap, stale);
    }

    /**
     * Returns a sensible default policy: a 10-minute overlap window and a 1-hour
     * stale-fetch budget.
     *
     * @return the default policy
     */
    public static KeyRotationPolicy defaults() {
        return new KeyRotationPolicy(DEFAULT_OVERLAP_WINDOW, DEFAULT_STALE_FETCH_BUDGET);
    }
}
