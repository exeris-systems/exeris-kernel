/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.core.bootstrap;

import java.time.Duration;
import java.util.Objects;

/**
 * Configuration of the initialization deadline: validation of the configured {@link Duration} and its
 * conversion to the nanosecond budget {@link InitDeadlineWatch} enforces.
 */
final class InitDeadlines {

    private InitDeadlines() {
        // static utility
    }

    /**
     * Converts {@code duration} to nanoseconds, saturating at {@link Long#MAX_VALUE} for a duration too long
     * to express, so that an unreachable deadline never wraps into a short one.
     *
     * @param duration the configured deadline
     * @return the deadline in nanoseconds; {@code 0} for {@link Duration#ZERO}
     */
    /* default */ static long budgetNanos(Duration duration) {
        try {
            return duration.toNanos();
        } catch (ArithmeticException _) {
            return Long.MAX_VALUE;
        }
    }

    /**
     * Validates a configured initialization deadline; the one check both builders apply.
     *
     * @param deadline the configured deadline
     * @return {@code deadline}, unchanged
     * @throws NullPointerException     if {@code deadline} is {@code null}
     * @throws IllegalArgumentException if {@code deadline} is negative, or positive but shorter than one
     *                                  millisecond, which the event and the exception could not report
     */
    /* default */ static Duration requireValidDeadline(Duration deadline) {
        Objects.requireNonNull(deadline, "deadline");
        if (deadline.isNegative()) {
            throw new IllegalArgumentException("initializeDeadline must not be negative: " + deadline);
        }
        if (!deadline.isZero() && deadline.compareTo(Duration.ofMillis(1)) < 0) {
            throw new IllegalArgumentException(
                    "initializeDeadline must be zero or at least 1 ms: " + deadline);
        }
        return deadline;
    }
}
