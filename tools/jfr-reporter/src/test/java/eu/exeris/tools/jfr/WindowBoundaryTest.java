/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.tools.jfr;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/** {@link TckMarker.Window#contains} at the edges, where the millisecond floor used to reach. */
class WindowBoundaryTest {

    private static final Instant START = Instant.parse("2026-09-11T10:00:00.004500000Z");
    private static final Instant END = Instant.parse("2026-09-11T10:00:00.009500000Z");

    private static final TckMarker.Window WINDOW = new TckMarker.Window(
            START, END, "EventBus", "EventBusTck", 10_000, 41L, "zero", -1, 0L, 1L);

    @Test
    @DisplayName("an event a fraction of a millisecond outside the window is outside it")
    void subMillisecondEdgesAreOutsideTheWindow() {
        // Both of these floor into the same millisecond as a boundary, which is how they used to
        // get in. The margin matters because it is exactly where the marker's own commit and the
        // sampler's noise sit - and a hot path running 10 000 iterations can finish inside it.
        assertThat(WINDOW.contains(START.minusNanos(500_000)))
                .as("half a millisecond before the window opened")
                .isFalse();
        assertThat(WINDOW.contains(END.plusNanos(500_000)))
                .as("half a millisecond after it closed")
                .isFalse();
    }

    @Test
    @DisplayName("the boundaries themselves are inside, and so is everything between them")
    void theWindowIsClosedAtBothEnds() {
        assertThat(WINDOW.contains(START)).isTrue();
        assertThat(WINDOW.contains(END)).isTrue();
        assertThat(WINDOW.contains(START.plusNanos(1))).isTrue();
        assertThat(WINDOW.contains(END.minusNanos(1))).isTrue();
    }
}
