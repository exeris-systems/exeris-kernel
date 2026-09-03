/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.spi.scheduling;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The accepted cron subset is a property of the carrier, not a rule drivers are trusted to follow
 * (ADR-057 §7) — so the boundary of the subset is asserted here, in the SPI.
 */
@DisplayName("JobTrigger — the accepted cron subset and its boundary")
class CronSyntaxTest {

    @Nested
    @DisplayName("Accepts the standard five-field syntax")
    class Accepted {

        @ParameterizedTest(name = "[{index}] {0}")
        @ValueSource(strings = {
                "* * * * *",
                "0 3 * * *",
                "*/15 * * * *",
                "0 0 1 1 *",
                "30 2 * * 0",
                "30 2 * * 7",
                "0,15,30,45 * * * *",
                "0 9-17 * * 1-5",
                "0 0-23/2 * * *",
                "59 23 31 12 6",
                "  0   3   *   *   *  ",
        })
        void acceptsValidExpression(String expression) {
            assertThatCode(() -> new JobTrigger.Cron(expression)).doesNotThrowAnyException();
        }
    }

    @Nested
    @DisplayName("Rejects everything outside the subset")
    class Rejected {

        @ParameterizedTest(name = "[{index}] {0}")
        @ValueSource(strings = {
                "* * * *",
                "* * * * * *",
                "@reboot",
                "@daily",
                "0 3 * * MON",
                "0 3 * JAN *",
                "*/0 * * * *",
                "60 * * * *",
                "* 24 * * *",
                "* * 0 * *",
                "* * * 13 *",
                "* * * * 8",
                "30-10 * * * *",
                "0 3 * * ?",
                "0 3 L * *",
                "0 3 * * 1#2",
                "0,, * * * *",
                "",
        })
        @DisplayName("a rejected expression is unrepresentable, so no driver can widen the subset")
        void rejectsInvalidExpression(String expression) {
            assertThatThrownBy(() -> new JobTrigger.Cron(expression))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("a seconds field is rejected as a field-count error, not silently ignored")
        void rejectsSecondsField() {
            assertThatThrownBy(() -> new JobTrigger.Cron("0 * * * * *"))
                    .as("six fields is the Quartz shape; accepting it would shift every schedule by "
                            + "a factor of sixty without any error")
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("5 fields");
        }
    }

    @Nested
    @DisplayName("Interval and one-shot triggers reject impossible durations")
    class Durations {

        @Test
        @DisplayName("a zero or negative interval is rejected — it would be a spin, not a schedule")
        void rejectsNonPositiveInterval() {
            assertThatThrownBy(() ->
                    new JobTrigger.FixedInterval(Duration.ZERO, Duration.ZERO))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() ->
                    new JobTrigger.FixedInterval(Duration.ZERO, Duration.ofSeconds(-1)))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("a negative delay is rejected on both trigger kinds")
        void rejectsNegativeDelay() {
            assertThatThrownBy(() -> new JobTrigger.OneShot(Duration.ofSeconds(-1)))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() ->
                    new JobTrigger.FixedInterval(Duration.ofSeconds(-1), Duration.ofMinutes(1)))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("a zero initial delay is accepted — running immediately is a real intent")
        void acceptsZeroInitialDelay() {
            assertThatCode(() ->
                    new JobTrigger.FixedInterval(Duration.ZERO, Duration.ofMinutes(1)))
                    .doesNotThrowAnyException();
            assertThatCode(() -> new JobTrigger.OneShot(Duration.ZERO))
                    .doesNotThrowAnyException();
        }
    }

    @Nested
    @DisplayName("Steps wider than their field are schedules, not typos")
    class WideSteps {

        @Test
        @DisplayName("a step at or past the field's span fires once per cycle and is accepted")
        void acceptsStepWiderThanField() {
            // The common spellings of "every hour" and "every day". Both stride past every value but
            // the first, so each fires exactly once per cycle. A bound at the field's upper value
            // would reject these two while admitting */59 and */23, which fire twice — backwards
            // from the reading that would motivate the bound, and a v0.10 schedule that stops
            // constructing on v0.11.
            assertThatCode(() -> new JobTrigger.Cron("0 */24 * * *")).doesNotThrowAnyException();
            assertThatCode(() -> new JobTrigger.Cron("*/60 * * * *")).doesNotThrowAnyException();
            assertThatCode(() -> new JobTrigger.Cron("* * * * */9")).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("a step of zero is still rejected — it is not a stride at all")
        void rejectsZeroStep() {
            assertThatThrownBy(() -> new JobTrigger.Cron("*/0 * * * *"))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("a three-digit step is rejected by the digit cap, not by a field bound")
        void rejectsThreeDigitStep() {
            // parseNumber's two-digit cap. Worth pinning because its javadoc used to claim it also
            // rejected steps wider than their field, which it never did.
            assertThatThrownBy(() -> new JobTrigger.Cron("*/100 * * * *"))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }
}
