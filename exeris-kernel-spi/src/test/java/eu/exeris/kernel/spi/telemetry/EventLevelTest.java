/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.spi.telemetry;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * L0 Contract: {@link EventLevel} ordinal stability.
 *
 * <h2>Why ordinals matter here</h2>
 * <p>The {@code BinaryGlassBoxSink} serialises the level as a single byte using
 * {@code ordinal()} to avoid heap-allocating the enum name. Any reordering
 * silently maps INFO events to FATAL and vice-versa in binary telemetry dumps.
 *
 * @since 0.5.0
 */
@DisplayName("L0: EventLevel — ordinal stability (BinaryGlassBoxSink byte contract)")
class EventLevelTest {

    @Test
    @DisplayName("INFO ordinal == 0")
    void infoOrdinal() {
        assertThat(EventLevel.INFO.ordinal()).isZero();
    }

    @Test
    @DisplayName("WARN ordinal == 1")
    void warnOrdinal() {
        assertThat(EventLevel.WARN.ordinal()).isEqualTo(1);
    }

    @Test
    @DisplayName("ERROR ordinal == 2")
    void errorOrdinal() {
        assertThat(EventLevel.ERROR.ordinal()).isEqualTo(2);
    }

    @Test
    @DisplayName("FATAL ordinal == 3")
    void fatalOrdinal() {
        assertThat(EventLevel.FATAL.ordinal()).isEqualTo(3);
    }

    @Test
    @DisplayName("Total count == 4 — new level requires BinaryGlassBoxSink decoder review")
    void totalCount() {
        assertThat(EventLevel.values()).hasSize(4);
    }

    @Test
    @DisplayName("Severity is ascending: INFO < WARN < ERROR < FATAL (by ordinal)")
    void severityAscending() {
        EventLevel[] levels = EventLevel.values();
        for (int i = 1; i < levels.length; i++) {
            assertThat(levels[i].ordinal())
                    .as("%s must have a higher ordinal than %s", levels[i], levels[i - 1])
                    .isGreaterThan(levels[i - 1].ordinal());
        }
    }

    @Test
    @DisplayName("valueOf() resolves all canonical names")
    void valueOfResolvesAll() {
        assertThat(EventLevel.valueOf("INFO")).isEqualTo(EventLevel.INFO);
        assertThat(EventLevel.valueOf("WARN")).isEqualTo(EventLevel.WARN);
        assertThat(EventLevel.valueOf("ERROR")).isEqualTo(EventLevel.ERROR);
        assertThat(EventLevel.valueOf("FATAL")).isEqualTo(EventLevel.FATAL);
    }
}
