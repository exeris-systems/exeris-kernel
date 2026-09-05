/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.spi.scheduling;

import java.time.Duration;
import java.util.Objects;

/**
 * When a job fires (ADR-057 §7).
 *
 * <p>Exactly three kinds, and the set is sealed so a driver cannot add a fourth. The ROADMAP entry
 * listed a fourth, event-driven; it is excluded because an event-triggered job is a subscription, and
 * the kernel already has one of those — routing it through a scheduler would create a second way to
 * consume events whose ordering and delivery guarantees would have to be re-specified.
 *
 * @since 0.11
 */
public sealed interface JobTrigger {

    /**
     * Fires on a five-field cron schedule.
     *
     * <p>The expression is validated here rather than in each driver, so a driver cannot quietly
     * widen the accepted subset — that guarantee is a property of the type, not a rule drivers are
     * asked to follow.
     *
     * @param expression a five-field cron expression, validated at construction
     * @implSpec Computing the next fire time from a valid expression is driver work. A compliant
     *           implementation must interpret day-of-month and day-of-week as OR'd when both fields
     *           are restricted (not AND'd), and must resolve every field in UTC rather than the
     *           host's local time zone.
     * @apiNote A step wider than its field is not an error: it strides past every value but the
     *          first, so it fires exactly once per cycle — {@code 0 &#42;/24 * * *} is therefore the
     *          common spelling of "daily", and {@code &#42;/23} on the same field fires twice
     *          (at 0 and 23) where {@code &#42;/24} fires once.
     */
    record Cron(String expression) implements JobTrigger {

        /**
         * Canonical constructor.
         *
         * @param expression a five-field cron expression, validated at construction
         * @throws NullPointerException     if {@code expression} is {@code null}
         * @throws IllegalArgumentException if the expression is not a valid five-field cron
         */
        public Cron {
            Objects.requireNonNull(expression, "expression must not be null");
            CronSyntax.requireValid(expression);
        }
    }

    /**
     * Fires repeatedly, waiting a fixed interval between the end of one run and the start of the next.
     *
     * @param initialDelay how long to wait before the first run; must not be negative
     * @param interval     the gap between runs; must be positive
     * @implSpec Fixed <em>delay</em>, not fixed <em>rate</em>: an implementation must delay the next
     *           run by the full interval measured from when the current run ends, and must not
     *           schedule a burst of catch-up runs when a run overruns its interval — catch-up is
     *           what turns a temporary slowdown into an outage.
     */
    record FixedInterval(Duration initialDelay, Duration interval) implements JobTrigger {

        /**
         * Canonical constructor.
         *
         * @param initialDelay how long to wait before the first run; must not be negative
         * @param interval     the gap between runs; must be positive
         * @throws NullPointerException     if either duration is {@code null}
         * @throws IllegalArgumentException if {@code initialDelay} is negative or {@code interval} is
         *                                  not positive
         */
        public FixedInterval {
            Objects.requireNonNull(initialDelay, "initialDelay must not be null");
            Objects.requireNonNull(interval, "interval must not be null");
            if (initialDelay.isNegative()) {
                throw new IllegalArgumentException("initialDelay must not be negative");
            }
            if (interval.isNegative() || interval.isZero()) {
                throw new IllegalArgumentException("interval must be positive");
            }
        }
    }

    /**
     * Fires once, after a delay.
     *
     * @param delay how long to wait before the single run; must not be negative
     */
    record OneShot(Duration delay) implements JobTrigger {

        /**
         * Canonical constructor.
         *
         * @param delay how long to wait before the single run; must not be negative
         * @throws NullPointerException     if {@code delay} is {@code null}
         * @throws IllegalArgumentException if {@code delay} is negative
         */
        public OneShot {
            Objects.requireNonNull(delay, "delay must not be null");
            if (delay.isNegative()) {
                throw new IllegalArgumentException("delay must not be negative");
            }
        }
    }
}
