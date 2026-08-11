/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.community.scheduling;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * What a validated expression <em>means</em>. The SPI proves which expressions are accepted; this
 * proves when they fire, which is the part a second driver could legitimately implement differently.
 */
@DisplayName("CommunityCronSchedule — next fire time")
class CommunityCronScheduleTest {

    /** A Tuesday, mid-month, mid-year: no month boundary, no weekend, no DST edge anywhere. */
    private static final Instant TUESDAY_NOON = Instant.parse("2026-03-10T12:00:00Z");

    /** Enough repeats to separate a bounded search from an unbounded one — see the case below. */
    private static final int REFUSAL_REPEATS = 20;

    private static Instant next(String expression, Instant after) {
        return new CommunityCronSchedule(expression).nextFireAfter(after);
    }

    @Nested
    @DisplayName("Basic schedules")
    class Basics {

        @Test
        @DisplayName("every minute fires at the next minute boundary")
        void everyMinute() {
            assertThat(next("* * * * *", Instant.parse("2026-03-10T12:00:30Z")))
                    .isEqualTo(Instant.parse("2026-03-10T12:01:00Z"));
        }

        @Test
        @DisplayName("the result is strictly after the reference instant, never equal to it")
        void strictlyAfter() {
            Instant onTheMinute = Instant.parse("2026-03-10T12:00:00Z");

            assertThat(next("* * * * *", onTheMinute))
                    .as("returning the reference instant would make a job fire twice for one deadline")
                    .isEqualTo(Instant.parse("2026-03-10T12:01:00Z"));
        }

        @Test
        @DisplayName("a daily schedule rolls to tomorrow once today's time has passed")
        void dailyRollsOver() {
            assertThat(next("0 3 * * *", TUESDAY_NOON))
                    .isEqualTo(Instant.parse("2026-03-11T03:00:00Z"));
        }

        @Test
        @DisplayName("a step schedule fires on its multiples")
        void stepSchedule() {
            assertThat(next("*/15 * * * *", Instant.parse("2026-03-10T12:01:00Z")))
                    .isEqualTo(Instant.parse("2026-03-10T12:15:00Z"));
        }

        @Test
        @DisplayName("a list schedule fires on its next listed value")
        void listSchedule() {
            assertThat(next("0,30 * * * *", Instant.parse("2026-03-10T12:05:00Z")))
                    .isEqualTo(Instant.parse("2026-03-10T12:30:00Z"));
        }

        @Test
        @DisplayName("a yearly schedule crosses the year boundary")
        void yearlySchedule() {
            assertThat(next("0 0 1 1 *", TUESDAY_NOON))
                    .isEqualTo(Instant.parse("2027-01-01T00:00:00Z"));
        }
    }

    @Nested
    @DisplayName("Day-of-week semantics")
    class DaysOfWeek {

        @Test
        @DisplayName("0 and 7 both mean Sunday")
        void sundayHasTwoSpellings() {
            assertThat(next("0 0 * * 0", TUESDAY_NOON))
                    .isEqualTo(next("0 0 * * 7", TUESDAY_NOON))
                    .isEqualTo(Instant.parse("2026-03-15T00:00:00Z"));
        }

        @Test
        @DisplayName("a weekday range skips the weekend")
        void weekdayRange() {
            // Friday 2026-03-13 at 09:00 -> next is Monday, not Saturday.
            assertThat(next("0 9 * * 1-5", Instant.parse("2026-03-13T09:30:00Z")))
                    .isEqualTo(Instant.parse("2026-03-16T09:00:00Z"));
        }

        @Test
        @DisplayName("day-of-month and day-of-week are OR'd when both are restricted")
        void domAndDowAreUnioned() {
            // The classic cron trap, stated in the contract rather than left to be discovered:
            // "1st of the month" OR "every Monday", not their intersection.
            Instant afterFirst = Instant.parse("2026-04-02T00:00:00Z");

            assertThat(next("0 0 1 * 1", afterFirst))
                    .as("2026-04-06 is the next Monday; the next 1st is 2026-05-01, so a union "
                            + "fires on the Monday and an intersection would not")
                    .isEqualTo(Instant.parse("2026-04-06T00:00:00Z"));
        }

        @Test
        @DisplayName("an unrestricted day-of-week does not widen a day-of-month schedule")
        void wildcardDowDoesNotUnion() {
            assertThat(next("0 0 1 * *", Instant.parse("2026-04-02T00:00:00Z")))
                    .as("with day-of-week unrestricted the union rule must not apply, or every "
                            + "day-of-month schedule would fire daily")
                    .isEqualTo(Instant.parse("2026-05-01T00:00:00Z"));
        }
    }

    @Nested
    @DisplayName("Calendar edges")
    class CalendarEdges {

        @Test
        @DisplayName("29 February resolves to the next leap year")
        void leapDay() {
            assertThat(next("0 0 29 2 *", TUESDAY_NOON))
                    .isEqualTo(Instant.parse("2028-02-29T00:00:00Z"));
        }

        @Test
        @DisplayName("an unsatisfiable expression refuses quickly, not after two million steps")
        void unsatisfiableExpressionRefusesPromptly() {
            // 30 February. The search bound used to be a STEP count sized as minutes-in-four-years,
            // while the search advances by days and months — so this walked 2 108 160 ZonedDateTime
            // operations, thousands of years past the horizon the message named, before refusing.
            // It does that inside CommunityJobScheduler.submit, under the scheduler's single lock.
            //
            // Cost is the only observable difference: this cron subset has no year field, so its
            // longest satisfiable gap is the eight-year leap-day one and the two bounds agree on
            // every expression that fires at all. Hence a timing assertion — repeated, because one
            // call does not separate them widely enough to assert on. Measured: 130 ms per call
            // under the step bound; 2 ms for the first call with the horizon and sub-millisecond
            // after. Twenty calls are therefore ~2.6 s against ~5 ms, and the threshold sits two
            // orders of magnitude below the defect and two above the fix — which is what keeps a
            // timing assertion off the flaky list.
            long startNanos = System.nanoTime();
            for (int repeat = 0; repeat < REFUSAL_REPEATS; repeat++) {
                assertThatThrownBy(() -> next("0 0 30 2 *", TUESDAY_NOON))
                        .isInstanceOf(IllegalStateException.class)
                        .hasMessageContaining("never fires");
            }
            long elapsedMillis = (System.nanoTime() - startNanos) / 1_000_000L;

            assertThat(elapsedMillis)
                    .as("a horizon in calendar time, not a step count")
                    .isLessThan(500L);
        }

        @Test
        @DisplayName("a leap day across a non-leap century still resolves")
        void leapDayAcrossNonLeapCentury() {
            // 2100 is not a leap year, so the gap here is eight years — the longest this subset can
            // produce, and the reason the horizon is eight rather than the four the old message
            // claimed. A four-year horizon would refuse a schedule that is perfectly satisfiable.
            assertThat(next("0 0 29 2 *", Instant.parse("2096-03-01T00:00:00Z")))
                    .isEqualTo(Instant.parse("2104-02-29T00:00:00Z"));
        }

        @Test
        @DisplayName("cron fields are read in UTC, not the host zone")
        void interpretedInUtc() {
            // Asserted rather than assumed: a driver switching to local time would silently move
            // every schedule, and would reintroduce the DST gap and overlap that UTC does not have.
            assertThat(next("0 0 * * *", Instant.parse("2026-03-10T23:30:00Z")))
                    .isEqualTo(Instant.parse("2026-03-11T00:00:00Z"));
        }
    }
}
