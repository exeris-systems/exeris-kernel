/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.core.persistence;

/**
 * Valhalla-ready retry policy used by {@link TransactionOrchestrator} and
 * related persistence components.
 *
 * <h2>Design</h2>
 * <p>Declared as a top-level {@code record} to provide a reusable, canonical
 * retry-policy abstraction decoupled from any particular orchestrator
 * implementation. All three components are primitives and there are no identity
 * operations, so the type carries nothing a future Valhalla value class would need to shed.
 * An orchestrator holds one shared instance for its lifetime and reads it on every attempt
 * rather than allocating a fresh policy per retry.
 *
 * @param maxAttempts       maximum total attempts (1 = no retry)
 * @param baseDelayMs       initial back-off delay in milliseconds
 * @param backoffMultiplier exponential multiplier applied after each retry
 * @since 0.5
 */
public record TransactionRetryPolicy(int maxAttempts, long baseDelayMs, double backoffMultiplier) {

    /** Default: 1 attempt, no retry. */
    public static final TransactionRetryPolicy NONE = new TransactionRetryPolicy(1, 0L, 1.0);

    private static final int    MIN_ATTEMPTS    = 1;
    private static final long   MIN_DELAY_MS    = 0L;
    private static final double MIN_MULTIPLIER  = 0.0;
    private static final int    FIRST_ATTEMPT   = 0;
    private static final long   MAX_DELAY_MS    = 30_000L;

    /**
     * Rejects an invalid policy at construction rather than at first use.
     *
     * @throws IllegalArgumentException if {@code maxAttempts} is below 1, {@code baseDelayMs} is
     *                                  negative, or {@code backoffMultiplier} is not a positive
     *                                  finite value
     */
    public TransactionRetryPolicy {
        if (maxAttempts < MIN_ATTEMPTS) {
            throw new IllegalArgumentException("maxAttempts must be >= 1, got: " + maxAttempts);
        }
        if (baseDelayMs < MIN_DELAY_MS) {
            throw new IllegalArgumentException("baseDelayMs must be >= 0, got: " + baseDelayMs);
        }
        if (backoffMultiplier <= MIN_MULTIPLIER || !Double.isFinite(backoffMultiplier)) {
            throw new IllegalArgumentException("backoffMultiplier must be > 0 and finite, got: " + backoffMultiplier);
        }
    }

    /**
     * Creates a policy with exponential back-off: {@code maxAttempts} total tries,
     * starting at {@code baseDelayMs} and doubling on each retry.
     *
     * @param maxAttempts maximum total attempts (1 = no retry)
     * @param baseDelayMs initial back-off delay in milliseconds
     * @return a policy with {@code backoffMultiplier} fixed at {@code 2.0}
     * @throws IllegalArgumentException if {@code maxAttempts} is below 1 or {@code baseDelayMs}
     *                                  is negative
     */
    public static TransactionRetryPolicy exponential(int maxAttempts, long baseDelayMs) {
        return new TransactionRetryPolicy(maxAttempts, baseDelayMs, 2.0);
    }

    /**
     * Computes the sleep delay before the given attempt.
     * Returns 0 for the first attempt and caps at 30 seconds.
     *
     * @param attempt 0-indexed attempt number
     * @return the delay in milliseconds to sleep before making this attempt
     * @throws IllegalArgumentException if {@code attempt} is negative
     */
    public long delayFor(int attempt) {
        if (attempt < 0) {
            throw new IllegalArgumentException("attempt must be >= 0, got: " + attempt);
        }
        if (attempt == FIRST_ATTEMPT || baseDelayMs <= MIN_DELAY_MS) {
            return MIN_DELAY_MS;
        }
        double delay = baseDelayMs * Math.pow(backoffMultiplier, attempt - 1.0);
        return (long) Math.min(delay, MAX_DELAY_MS);
    }
}

