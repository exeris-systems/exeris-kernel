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
 * @since 0.10.0
 */
public record RetryDecision(boolean retry, long delayMillis) {

    private static final RetryDecision GIVE_UP = new RetryDecision(false, 0L);

    /**
     * Canonical constructor; rejects a negative delay on a retry verdict.
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
     * @return a retry verdict
     */
    public static RetryDecision retryAfter(long delayMillis) {
        return new RetryDecision(true, delayMillis);
    }

    /**
     * Returns the stable give-up verdict (no further attempts).
     *
     * @return the give-up verdict
     */
    public static RetryDecision giveUp() {
        return GIVE_UP;
    }
}
