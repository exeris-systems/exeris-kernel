/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.scheduling;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;

/**
 * What a validated five-field cron expression means: the next instant it fires.
 *
 * <p>The SPI validates the syntax ({@code CronSyntax}), so this class may assume a well-formed
 * expression and concern itself only with interpretation. The split is deliberate — the accepted
 * subset is a property of the carrier, while its meaning is driver work that a second driver could
 * legitimately implement differently.
 *
 * <h2>UTC, not local time</h2>
 * <p>Cron fields are interpreted in UTC. Local time would make every schedule depend on the host's
 * zone database and would expose the two classic DST defects — a daily job that runs twice on the
 * autumn overlap, and one that silently skips the spring gap. Neither exists in UTC. A driver
 * choosing local time would be changing observable behaviour and would have to say so.
 *
 * <h2>Day-of-month and day-of-week</h2>
 * <p>Standard cron semantics: when both fields are restricted, a day matches if <em>either</em> does.
 * This surprises people, so it is stated rather than left to be discovered — {@code 0 0 1 * 1} fires
 * on the first of the month <em>and</em> every Monday, not on Mondays that fall on the first.
 *
 * @since 0.11
 */
final class CommunityCronSchedule {

    /**
     * Bound on the search, in <em>calendar time</em> rather than in steps. Past it the expression is
     * unsatisfiable (30 February, say), and looping forever would turn a typo into a hung dispatcher.
     *
     * <p>Expressed in years rather than steps because the search advances coarsest-field-first: an
     * unsatisfiable expression steps by days and months, so a step-count bound is spent walking
     * years of calendar time it was meant to cap directly, instead of holding the calendar horizon
     * itself. This runs inside {@code CommunityJobScheduler.submit}, which holds the scheduler's
     * single lock, so a cheap refusal matters — one bad expression otherwise stalls every other
     * submit, cancel and dispatch behind it. A year-based horizon costs one comparison per step.
     *
     * <p>Eight years: the longest gap between consecutive 29 Februaries is eight, across a century
     * that is not a leap year (2096 to 2104). A shorter horizon would refuse a legitimate leap-day
     * schedule that falls inside such a gap.
     */
    private static final int HORIZON_YEARS = 8;

    private static final int MINUTE_COUNT = 60;
    private static final int HOUR_COUNT = 24;
    private static final int DAY_OF_MONTH_COUNT = 32;
    private static final int MONTH_COUNT = 13;
    private static final int DAY_OF_WEEK_COUNT = 7;
    private static final int SUNDAY_ALIAS = 7;

    private static final String WILDCARD = "*";
    private static final char RANGE = '-';
    private static final char STEP_SEPARATOR = '/';

    private final boolean[] minutes = new boolean[MINUTE_COUNT];
    private final boolean[] hours = new boolean[HOUR_COUNT];
    private final boolean[] daysOfMonth = new boolean[DAY_OF_MONTH_COUNT];
    private final boolean[] months = new boolean[MONTH_COUNT];
    private final boolean[] daysOfWeek = new boolean[DAY_OF_WEEK_COUNT];

    private final boolean domRestricted;
    private final boolean dowRestricted;

    /* default */ CommunityCronSchedule(String expression) {
        String[] fields = expression.strip().split("\\s+");
        fill(minutes, fields[0], 0);
        fill(hours, fields[1], 0);
        fill(daysOfMonth, fields[2], 1);
        fill(months, fields[3], 1);
        fillDaysOfWeek(fields[4]);
        this.domRestricted = !isWildcard(fields[2]);
        this.dowRestricted = !isWildcard(fields[4]);
    }

    /**
     * The first instant strictly after {@code after} at which this expression fires.
     *
     * @param after the reference instant
     * @return the next fire time, truncated to the minute
     * @throws IllegalStateException if the expression cannot fire within {@link #HORIZON_YEARS} years
     */
    /* default */ Instant nextFireAfter(Instant after) {
        ZonedDateTime candidate = after.atZone(ZoneOffset.UTC)
                .truncatedTo(ChronoUnit.MINUTES)
                .plusMinutes(1);
        ZonedDateTime horizon = candidate.plusYears(HORIZON_YEARS);

        while (!candidate.isAfter(horizon)) {
            ZonedDateTime advanced = advanceToNextCandidate(candidate);
            if (advanced == null) {
                return candidate.toInstant();
            }
            candidate = advanced;
        }
        throw new IllegalStateException(
                "cron expression never fires within " + HORIZON_YEARS + " years");
    }

    /**
     * Advances past the coarsest field that does not match, or returns {@code null} when every field
     * matches and the candidate is the answer.
     *
     * <p>Coarsest-first is what keeps the search short: a schedule restricted to one month skips
     * eleven months per step rather than walking minutes through them.
     */
    private ZonedDateTime advanceToNextCandidate(ZonedDateTime candidate) {
        if (!months[candidate.getMonthValue()]) {
            return candidate.plusMonths(1).withDayOfMonth(1).truncatedTo(ChronoUnit.DAYS);
        }
        if (!dayMatches(candidate)) {
            return candidate.plusDays(1).truncatedTo(ChronoUnit.DAYS);
        }
        if (!hours[candidate.getHour()]) {
            return candidate.plusHours(1).truncatedTo(ChronoUnit.HOURS);
        }
        if (!minutes[candidate.getMinute()]) {
            return candidate.plusMinutes(1);
        }
        return null;
    }

    private boolean dayMatches(ZonedDateTime candidate) {
        boolean byDom = daysOfMonth[candidate.getDayOfMonth()];
        // DayOfWeek is MONDAY=1..SUNDAY=7; cron is SUNDAY=0..SATURDAY=6.
        boolean byDow = daysOfWeek[candidate.getDayOfWeek().getValue() % SUNDAY_ALIAS];
        if (domRestricted && dowRestricted) {
            return byDom || byDow;
        }
        return byDom && byDow;
    }

    private static boolean isWildcard(String field) {
        return WILDCARD.equals(field);
    }

    private void fillDaysOfWeek(String field) {
        // Parsed over 0..7 so the Sunday alias is representable, then folded down to 0..6.
        boolean[] wide = new boolean[SUNDAY_ALIAS + 1];
        fill(wide, field, 0);
        System.arraycopy(wide, 0, daysOfWeek, 0, DAY_OF_WEEK_COUNT);
        daysOfWeek[0] = daysOfWeek[0] || wide[SUNDAY_ALIAS];
    }

    private static void fill(boolean[] target, String field, int offset) {
        for (String term : field.split(",", -1)) {
            fillTerm(target, term, offset);
        }
    }

    private static void fillTerm(boolean[] target, String term, int offset) {
        int slash = term.indexOf(STEP_SEPARATOR);
        String range = slash < 0 ? term : term.substring(0, slash);
        int step = slash < 0 ? 1 : Integer.parseInt(term.substring(slash + 1));

        int lower;
        int upper;
        if (WILDCARD.equals(range)) {
            lower = offset;
            upper = target.length - 1;
        } else {
            int dash = range.indexOf(RANGE);
            if (dash < 0) {
                lower = Integer.parseInt(range);
                // A bare value with a step means "from here to the end", the standard cron reading.
                upper = slash < 0 ? lower : target.length - 1;
            } else {
                lower = Integer.parseInt(range.substring(0, dash));
                upper = Integer.parseInt(range.substring(dash + 1));
            }
        }
        for (int value = lower; value <= upper && value < target.length; value += step) {
            target[value] = true;
        }
    }
}
