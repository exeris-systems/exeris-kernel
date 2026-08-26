/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
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
     * Parses one to two digits.
     *
     * <p>The two-digit cap is not arbitrary: the largest value any field admits is 59, so a longer
     * run of digits cannot be in bounds anyway, and capping the length means the parse cannot
     * overflow and needs no {@code NumberFormatException} path.
     *
     * <p>It does <em>not</em> also reject a step wider than its field, which this javadoc claimed
     * until v0.11: the cap counts digits, so it stops a step of 100 and admits one of 99. Nothing
     * else rejects it either, and deliberately. A step wider than its field is not nonsense — it
     * strides past every value but the first, so it fires exactly once per field cycle, which is
     * what {@code 0 &#42;/24 * * *} means as the common spelling of "daily". A bound at the field's
     * upper value would have admitted a step of 59 on minutes, which fires twice an hour, while
     * rejecting 60, which fires once: backwards from the reading that motivates it.
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
