/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.spi.scheduling;

/**
 * What values each cron field admits, and how a number is read.
 *
 * <p>Split from {@link CronSyntax}, which walks the grammar — lists, ranges, steps — without caring
 * what a number means. Keeping the two apart means widening a field's range is not a change to the
 * grammar, and vice versa.
 *
 * @since 0.11.0
 */
final class CronFieldBounds {

    private static final int MAX_DIGITS = 2;

    /** Inclusive lower bound per field, indexed as minute, hour, day-of-month, month, day-of-week. */
    private static final int[] MIN = {0, 0, 1, 1, 0};
    /** Inclusive upper bound per field; day-of-week allows 7 as a second spelling of Sunday. */
    private static final int[] MAX = {59, 23, 31, 12, 7};

    private CronFieldBounds() {
        // Utility holder — not instantiable.
    }

    /* default */ static void requireInBounds(int value, int index) {
        if (value < MIN[index] || value > MAX[index]) {
            throw new IllegalArgumentException(
                    "cron value " + value + " is outside " + MIN[index] + "-" + MAX[index]
                            + " for field " + (index + 1));
        }
    }

    /**
     * Rejects a step wider than the field it steps through.
     *
     * <p>A step is a stride, not a value, so it is not bounded by {@link #requireInBounds}: a step of
     * 30 on the minute field is a legitimate half-hourly schedule even though 30 is not a minute
     * anyone asked for. What it cannot exceed is the field's own span. A step of 9 on day-of-week
     * strides past every day there is, so the schedule fires on the first value and never again
     * within the field — a typo with a plausible reading, and reading it silently is worse than
     * refusing it.
     */
    /* default */ static void requireStepInBounds(int step, int index) {
        if (step > MAX[index]) {
            throw new IllegalArgumentException(
                    "cron step " + step + " is wider than field " + (index + 1)
                            + ", which spans " + MIN[index] + "-" + MAX[index]);
        }
    }

    /**
     * Parses one to two digits.
     *
     * <p>The two-digit cap is not arbitrary: the largest value any field admits is 59, so a longer
     * run of digits cannot be in bounds anyway, and capping the length means the parse cannot
     * overflow and needs no {@code NumberFormatException} path.
     *
     * <p>It does <em>not</em> also reject a step wider than its field, which this javadoc claimed
     * until v0.11. The cap counts digits, so it stops a step of 100 and admits one of 99 — and on
     * day-of-week it admits everything from 7 up. {@link #requireStepInBounds} is what actually
     * enforces that, and {@link CronSyntax} calls it.
     */
    /* default */ static int parseNumber(String text, int index) {
        if (text.isEmpty() || text.length() > MAX_DIGITS) {
            throw new IllegalArgumentException(
                    "cron field " + (index + 1) + " must be one or two digits, got: " + text);
        }
        for (int i = 0; i < text.length(); i++) {
            if (!Character.isDigit(text.charAt(i))) {
                // Rejects name aliases (MON, JAN) here rather than in a separate check: anything
                // non-numeric lands in this branch, so widening the subset cannot happen by omission.
                throw new IllegalArgumentException(
                        "cron field " + (index + 1) + " must be numeric, got: " + text);
            }
        }
        return Integer.parseInt(text);
    }
}
