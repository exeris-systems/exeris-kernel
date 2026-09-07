/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.spi.http;

/**
 * SPI value carrier: the verdict of an {@link HttpRetryPolicy} for a single send
 * attempt — either retry after {@code delayMillis}, or give up and surface the
 * outcome to the caller.
 *
 * <p>Immutable, identity-free, Valhalla-ready (per ADR-045). {@code delayMillis}
 * is meaningful only when {@code retry} is {@code true} and MUST be {@code >= 0}.
 *
 * @param retry       whether the attempt should be retried
 * @param delayMillis delay before the next attempt in milliseconds; {@code >= 0}
 * @since 0.10
 */
public record RetryDecision(boolean retry, long delayMillis) {

    private static final RetryDecision GIVE_UP = new RetryDecision(false, 0L);

    /**
     * Rejects a negative delay on a retry verdict, so a policy cannot instruct the façade to sleep
     * for a time that has no meaning.
     *
     * <p>A give-up verdict carries no delay obligation, so {@code delayMillis} is not validated when
     * {@code retry} is {@code false}.
     *
     * @throws IllegalArgumentException if {@code retry} is {@code true} and {@code delayMillis} is
     *                                  negative
     */
    public RetryDecision {
        if (retry && delayMillis < 0L) {
            throw new IllegalArgumentException("retry delayMillis must be >= 0, was " + delayMillis);
        }
    }

    /**
     * Returns a verdict to retry after {@code delayMillis}.
     *
     * @param delayMillis delay before the next attempt; {@code >= 0}
     * @return a verdict instructing the façade to sleep {@code delayMillis} and re-issue the request
     * @throws IllegalArgumentException if {@code delayMillis} is negative
     */
    public static RetryDecision retryAfter(long delayMillis) {
        return new RetryDecision(true, delayMillis);
    }

    /**
     * Returns the stable give-up verdict (no further attempts).
     *
     * @return a shared verdict whose {@link #retry()} is {@code false}, instructing the façade to
     *         surface this attempt's outcome to the caller
     */
    public static RetryDecision giveUp() {
        return GIVE_UP;
    }
}
