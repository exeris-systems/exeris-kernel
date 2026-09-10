/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.spi.time;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The {@link Clock} view has to stay attached to the source (ADR-082).
 *
 * <p>The first draft of the adapter it replaced used {@code Clock.fixed(wallTime(), UTC)}. That
 * compiles, type-checks, and freezes at the instant the adapter was built — so a consumer holding it
 * never sees a virtual source move, and the seam silently does nothing for exactly the case it
 * exists to serve. A test asserting only "the clock reads the source" would have passed against it.
 */
@DisplayName("TimeSource")
class TimeSourceTest {

    @Nested
    @DisplayName("asClock()")
    class AsClock {

        @Test
        @DisplayName("follows the source after it moves — the half a frozen adapter fails")
        void followsTheSource() {
            MovableSource source = new MovableSource();
            Clock clock = source.asClock();
            Instant before = clock.instant();

            source.advanceSeconds(600);

            assertThat(clock.instant())
                    .as("a Clock.fixed adapter returns `before` here and the seam does nothing")
                    .isEqualTo(before.plusSeconds(600));
        }

        @Test
        @DisplayName("reads UTC, so an instant carries no zone offset nobody asked for")
        void readsUtc() {
            assertThat(new MovableSource().asClock().getZone()).isEqualTo(ZoneOffset.UTC);
        }

        @Test
        @DisplayName("withZone keeps delegating rather than handing back a stopped clock")
        void withZoneStaysLive() {
            MovableSource source = new MovableSource();
            Clock zoned = source.asClock().withZone(ZoneOffset.ofHours(2));
            Instant before = zoned.instant();

            source.advanceSeconds(60);

            assertThat(zoned.instant()).isEqualTo(before.plusSeconds(60));
        }

        @Test
        @DisplayName("withZone actually changes the zone — this is a public Clock, not a kernel-only one")
        void withZoneHonoursTheZone() {
            // The first version returned `this`, so getZone() still said UTC after a caller asked
            // for something else. Harmless for the kernel's two consumers, which compare instants,
            // and wrong for anything zone-shaped a future caller builds on a public SPI Clock.
            ZoneOffset plusTwo = ZoneOffset.ofHours(2);

            assertThat(new MovableSource().asClock().withZone(plusTwo).getZone()).isEqualTo(plusTwo);
        }
    }

    @Nested
    @DisplayName("SYSTEM")
    class System {

        @Test
        @DisplayName("reads the platform clock and moves")
        void systemMoves() {
            long first = TimeSource.SYSTEM.nanoTime();
            long second = TimeSource.SYSTEM.nanoTime();

            assertThat(second).isGreaterThanOrEqualTo(first);
            assertThat(TimeSource.SYSTEM.wallTime()).isNotNull();
        }
    }

    private static final class MovableSource implements TimeSource {

        private final AtomicLong seconds = new AtomicLong();

        @Override
        public long nanoTime() {
            return seconds.get() * 1_000_000_000L;
        }

        @Override
        public Instant wallTime() {
            return Instant.parse("2026-09-01T00:00:00Z").plusSeconds(seconds.get());
        }

        void advanceSeconds(long by) {
            seconds.addAndGet(by);
        }
    }
}
