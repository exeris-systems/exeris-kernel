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
 * Valhalla-ready retry policy for {@link TransactionOrchestrator}.
 *
 * <h2>Design</h2>
 * <p>Declared as a top-level {@code record} so that PMD
 * {@code FieldDeclarationsShouldBeAtStartOfClass} is satisfied in
 * {@link TransactionOrchestrator} (no inner class after fields).
 * No identity operations — scalarizes via JIT Escape Analysis on hot path.
 *
 * @param maxAttempts       maximum total attempts (1 = no retry)
 * @param baseDelayMs       initial back-off delay in milliseconds
 * @param backoffMultiplier exponential multiplier applied after each retry
 * @since 0.5.0
 */
public record TransactionRetryPolicy(int maxAttempts, long baseDelayMs, double backoffMultiplier) {

    /** Default: 1 attempt, no retry. */
    public static final TransactionRetryPolicy NONE = new TransactionRetryPolicy(1, 0L, 1.0);

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
        if (attempt == 0 || baseDelayMs <= 0) {
            return 0L;
        }
        double delay = baseDelayMs * Math.pow(backoffMultiplier, attempt - 1.0);
        return (long) Math.min(delay, 30_000.0);
    }
}

