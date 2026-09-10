/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.spi.scheduling;

/**
 * The cron subset the kernel accepts, and the boundary of it (ADR-057 §7).
 *
 * <p>Five numeric fields, separated by whitespace:
 * {@code minute hour day-of-month month day-of-week}, with ranges {@code 0-59}, {@code 0-23},
 * {@code 1-31}, {@code 1-12}, {@code 0-7} (both {@code 0} and {@code 7} meaning Sunday).
 *
 * <p>Each field is a comma-separated list of terms, where a term is {@code *}, a value, a
 * {@code lo-hi} range, or either of those followed by {@code /step}.
 *
 * <p><strong>Deliberately absent:</strong> a seconds field, {@code @reboot} and friends, {@code ?},
 * {@code L}, {@code W}, {@code #}, and <em>name aliases</em> such as {@code MON} or {@code JAN}.
 * Names are excluded despite being common, because implementations disagree on whether day-of-week
 * {@code 0} is Sunday or Monday and the alias spelling hides that disagreement; numbers make the
 * ambiguity visible, and the {@code 0}/{@code 7} rule above resolves it.
 *
 * <p>Validation lives in the SPI so the subset is a property of the carrier rather than a rule each
 * driver is trusted to enforce. What a valid expression <em>means</em> — the next fire time — stays
 * driver work.
 *
 * @since 0.11
 */
final class CronSyntax {

    private static final int FIELD_COUNT = 5;
    private static final String WILDCARD = "*";
    private static final char RANGE = '-';
    private static final char STEP = '/';
    private static final char LIST = ',';
    private static final int MIN_STEP = 1;


    private CronSyntax() {
        // Utility holder — not instantiable.
    }

    /* default */ static void requireValid(String expression) {
        String[] fields = expression.strip().split("\\s+");
        if (fields.length != FIELD_COUNT) {
            throw new IllegalArgumentException(
                    "cron expression must have exactly 5 fields, got " + fields.length);
        }
        for (int i = 0; i < FIELD_COUNT; i++) {
            requireValidField(fields[i], i);
        }
    }

    private static void requireValidField(String field, int index) {
        for (String term : field.split(String.valueOf(LIST), -1)) {
            requireValidTerm(term, index);
        }
    }

    private static void requireValidTerm(String term, int index) {
        if (term.isEmpty()) {
            throw new IllegalArgumentException("cron field must not contain an empty term");
        }
        int slash = term.indexOf(STEP);
        if (slash < 0) {
            requireValidRange(term, index);
            return;
        }
        requireValidRange(term.substring(0, slash), index);
        requireStep(term.substring(slash + 1), index);
    }

    private static void requireStep(String step, int index) {
        int value = CronFieldBounds.parseNumber(step, index);
        if (value < MIN_STEP) {
            throw new IllegalArgumentException("cron step must be at least 1, got " + value);
        }
    }

    private static void requireValidRange(String range, int index) {
        if (WILDCARD.equals(range)) {
            return;
        }
        int dash = range.indexOf(RANGE);
        if (dash < 0) {
            CronFieldBounds.requireInBounds(CronFieldBounds.parseNumber(range, index), index);
            return;
        }
        int lower = CronFieldBounds.parseNumber(range.substring(0, dash), index);
        int upper = CronFieldBounds.parseNumber(range.substring(dash + 1), index);
        CronFieldBounds.requireInBounds(lower, index);
        CronFieldBounds.requireInBounds(upper, index);
        if (lower > upper) {
            throw new IllegalArgumentException("cron range is inverted: " + lower + "-" + upper);
        }
    }


}
