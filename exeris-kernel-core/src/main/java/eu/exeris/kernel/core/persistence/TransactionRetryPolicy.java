/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.core.persistence;

/**
 * Valhalla-ready retry policy used by {@link TransactionOrchestrator} and
 * related persistence components.
 *
 * <h2>Design</h2>
 * <p>Declared as a top-level {@code record} to provide a reusable, canonical
 * retry-policy abstraction decoupled from any particular orchestrator
 * implementation. No identity operations — scalarizes via JIT Escape Analysis
 * on hot path.
 *
 * @param maxAttempts       maximum total attempts (1 = no retry)
 * @param baseDelayMs       initial back-off delay in milliseconds
 * @param backoffMultiplier exponential multiplier applied after each retry
 * @since 0.5.0
 */
public record TransactionRetryPolicy(int maxAttempts, long baseDelayMs, double backoffMultiplier) {

    /** Default: 1 attempt, no retry. */
    public static final TransactionRetryPolicy NONE = new TransactionRetryPolicy(1, 0L, 1.0);

    private static final int    MIN_ATTEMPTS    = 1;
    private static final long   MIN_DELAY_MS    = 0L;
    private static final double MIN_MULTIPLIER  = 0.0;
    private static final int    FIRST_ATTEMPT   = 0;
    private static final long   MAX_DELAY_MS    = 30_000L;

    public TransactionRetryPolicy {
        if (maxAttempts < MIN_ATTEMPTS) {
            throw new IllegalArgumentException("maxAttempts must be >= 1, got: " + maxAttempts);
        }
        if (baseDelayMs < MIN_DELAY_MS) {
            throw new IllegalArgumentException("baseDelayMs must be >= 0, got: " + baseDelayMs);
        }
        if (backoffMultiplier <= MIN_MULTIPLIER) {
            throw new IllegalArgumentException("backoffMultiplier must be > 0, got: " + backoffMultiplier);
        }
    }

    /**
     * Exponential back-off: {@code maxAttempts} total tries,
     * starting at {@code baseDelayMs} and doubling on each retry.
     */
    public static TransactionRetryPolicy exponential(int maxAttempts, long baseDelayMs) {
        return new TransactionRetryPolicy(maxAttempts, baseDelayMs, 2.0);
    }

    /**
     * Computes the sleep delay before attempt {@code attempt} (0-indexed).
     * Returns 0 for the first attempt and caps at 30 seconds.
     */
    public long delayFor(int attempt) {
        if (attempt == FIRST_ATTEMPT || baseDelayMs <= MIN_DELAY_MS) {
            return MIN_DELAY_MS;
        }
        double delay = baseDelayMs * Math.pow(backoffMultiplier, attempt - 1.0);
        return (long) Math.min(delay, MAX_DELAY_MS);
    }
}

