/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.spi.time;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

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

    /**
     * This source as a {@link Clock}, for consumers shaped around the JDK's abstraction.
     *
     * <p><b>Live, not a snapshot.</b> The returned clock delegates every {@code instant()} call, so
     * a virtual source the caller later advances is visible through it. {@code Clock.fixed(wallTime(),
     * …)} is the tempting one-liner and it is wrong: it freezes at the moment the adapter was built,
     * which for a virtual clock means the consumer never sees it move — a seam that compiles,
     * type-checks, and silently does not work.
     *
     * <p>Fixed at UTC, which is what {@code Clock.systemUTC()} gives and what every consumer of this
     * in the kernel wants: these are instants compared against token and rotation deadlines, where
     * a local zone would add an offset nobody asked for.
     *
     * @return a clock reading this source; never {@code null}
     */
    default Clock asClock() {
        TimeSource self = this;
        return new Clock() {

            @Override
            public ZoneId getZone() {
                return ZoneOffset.UTC;
            }

            @Override
            public Clock withZone(ZoneId zone) {
                // The kernel's consumers compare instants, so a zone change would alter nothing an
                // instant carries. Returning this keeps the delegation live rather than handing back
                // a Clock that has stopped following the source.
                return this;
            }

            @Override
            public Instant instant() {
                return self.wallTime();
            }

            @Override
            public String toString() {
                return "TimeSource.asClock[" + self + ']';
            }
        };
    }
}
