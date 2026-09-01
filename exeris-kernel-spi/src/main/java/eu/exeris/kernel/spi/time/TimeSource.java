/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.spi.time;

import java.time.Instant;

/**
 * SPI: where the kernel reads time it will <em>decide</em> on (ADR-082).
 *
 * <h2>Two reads, because one is not enough</h2>
 * <p>A deadline is monotonic and a persisted timeout is calendar time, and the kernel converts
 * between them — {@code RuntimeFlowInstance} turns a saga's stored {@code Instant} timeout into a
 * {@code nanoTime} deadline and back. Virtualising one clock and not the other makes that conversion
 * drift, so a single-method seam could not drive the path this one exists for.
 *
 * <h2>What goes through here, and what does not</h2>
 * <p>Reads that <b>decide</b> — compared against a deadline, persisted as an expiry — go through a
 * {@code TimeSource}. Reads that <b>measure</b> — bracketing an operation to report how long it
 * took — do not: virtualising those would make JFR durations lie, and a seam inside a spin loop adds
 * an indirection on a hot path for no determinism. ADR-082 carries the table.
 *
 * <h2>Relationship to the scheduler's clock</h2>
 * <p>{@code CommunitySchedulerClock} is this interface <em>plus</em> waiting primitives, and it
 * predates it — the method names here are taken from it rather than invented, so the scheduler
 * keeps its `awaitUntil`/`awaitSignal` without owning a second definition of what time is.
 *
 * <p>Implementations must be thread-safe: one source serves the whole runtime.
 *
 * @since 0.12.0
 */
public interface TimeSource {

    /**
     * The platform clock. What an unbound kernel reads.
     */
    TimeSource SYSTEM = new TimeSource() {

        @Override
        public long nanoTime() {
            return System.nanoTime();
        }

        @Override
        public Instant wallTime() {
            return Instant.now();
        }

        @Override
        public String toString() {
            return "TimeSource.SYSTEM";
        }
    };

    /**
     * Monotonic nanoseconds, for deadlines.
     *
     * <p>Comparable only against itself and only within one process — the same contract
     * {@link System#nanoTime()} carries, and for the same reason. A value from this method must
     * never be persisted or compared across a restart; {@link #wallTime()} is what survives one.
     *
     * @return monotonically non-decreasing nanoseconds from an arbitrary origin
     */
    long nanoTime();

    /**
     * Calendar time, for values that outlive the process.
     *
     * <p>A saga's persisted timeout is the motivating case: it is written by one process and
     * compared by another, so it cannot be monotonic.
     *
     * @return the current instant; never {@code null}
     */
    Instant wallTime();
}
