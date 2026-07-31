/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
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
 * @since 0.11.0
 */
public sealed interface JobTrigger {

    /**
     * Fires on a five-field cron schedule.
     *
     * <p>The expression is validated here rather than in each driver, so obligation 7's "a driver
     * cannot quietly widen it" is a property of the type rather than a rule drivers are asked to
     * follow. Computing the next fire time from a valid expression remains driver work.
     *
     * @param expression a five-field cron expression, validated at construction
     */
    record Cron(String expression) implements JobTrigger {

        /**
         * Canonical constructor.
         *
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
     * <p>Fixed <em>delay</em>, not fixed <em>rate</em>: a run that overruns its interval delays the
     * next one rather than causing a burst of catch-up runs. Catch-up is the behaviour that turns a
     * temporary slowdown into an outage, so the contract does not offer it.
     *
     * @param initialDelay how long to wait before the first run; must not be negative
     * @param interval     the gap between runs; must be positive
     */
    record FixedInterval(Duration initialDelay, Duration interval) implements JobTrigger {

        /**
         * Canonical constructor.
         *
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
