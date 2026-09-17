/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.core.telemetry.jfr;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Contract of the catalogue every module's JFR event classes are split by, and of the warm-up it
 * drives.
 *
 * <p>The point of the class is that initialisation happens on the thread that starts a subsystem
 * instead of later, on a virtual thread, where a {@code <clinit>} pins the carrier. So "it ran the
 * initialiser" is the assertion, not "it did not throw" — a warm-up that quietly did nothing would
 * leave that pin exactly where it was.
 */
@DisplayName("JfrEventCatalogue: warms what a subsystem starts, and answers for what it does not")
class JfrEventCatalogueTest {

    private static final AtomicBoolean PROBE_INITIALISED = new AtomicBoolean();

    private static final String PROBE = "eu.exeris.kernel.core.telemetry.jfr.JfrEventCatalogueTest$Probe";
    private static final String ABSENT = "eu.exeris.kernel.core.telemetry.jfr.NoSuchEventClassAnywhere";

    @Nested
    @DisplayName("Warming")
    class Warming {

        @Test
        @DisplayName("runs the static initialiser of a class the catalogue names")
        void warmingRunsTheStaticInitialiser() {
            JfrEventCatalogue catalogue =
                    new JfrEventCatalogue(JfrEventCatalogueTest.class, Map.of("probe", List.of(PROBE)), List.of());

            assertThat(PROBE_INITIALISED)
                    .withFailMessage("Probe initialised before the warm-up ran; the assertion below would be vacuous")
                    .isFalse();

            catalogue.warmHotPath("probe");

            assertThat(PROBE_INITIALISED).isTrue();
        }

        @Test
        @DisplayName("a name that does not resolve is survivable — a subsystem still starts")
        void anUnresolvableNameDoesNotFailTheCaller() {
            JfrEventCatalogue catalogue =
                    new JfrEventCatalogue(JfrEventCatalogueTest.class, Map.of("ghost", List.of(ABSENT)), List.of());

            assertThatCode(() -> catalogue.warmHotPath("ghost")).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("a subsystem the catalogue does not cover warms nothing, and does not throw")
        void anUnknownSubsystemWarmsNothing() {
            JfrEventCatalogue catalogue =
                    new JfrEventCatalogue(JfrEventCatalogueTest.class, Map.of("known", List.of(PROBE)), List.of());

            assertThat(catalogue.hotPathFor("unknown")).isEmpty();
            assertThatCode(() -> catalogue.warmHotPath("unknown")).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("a null subsystem name is a lookup miss, not a failure")
        void aNullSubsystemNameIsAMiss() {
            JfrEventCatalogue catalogue =
                    new JfrEventCatalogue(JfrEventCatalogueTest.class, Map.of("known", List.of(PROBE)), List.of());

            assertThat(catalogue.hotPathFor(null)).isEmpty();
            assertThatCode(() -> catalogue.warmHotPath(null)).doesNotThrowAnyException();
        }
    }

    @Nested
    @DisplayName("Reading")
    class Reading {

        private final JfrEventCatalogue catalogue = new JfrEventCatalogue(
                JfrEventCatalogueTest.class,
                Map.of("transport", List.of("a.Alpha", "a.Beta"), "http", List.of("b.Gamma")),
                List.of("c.Cold"));

        @Test
        @DisplayName("hot-path classes are readable per subsystem and in total")
        void readsBothWays() {
            assertThat(catalogue.hotPathFor("transport")).containsExactly("a.Alpha", "a.Beta");
            assertThat(catalogue.allHotPath()).containsExactlyInAnyOrder("a.Alpha", "a.Beta", "b.Gamma");
        }

        @Test
        @DisplayName("the warmed subsystem names come back sorted, so a report is stable")
        void warmedSubsystemsAreSorted() {
            assertThat(catalogue.warmedSubsystems()).containsExactly("http", "transport");
        }

        @Test
        @DisplayName("the cold bucket is readable — it is what the guard test matches against")
        void coldBucketIsReadable() {
            assertThat(catalogue.deliberatelyCold()).containsExactly("c.Cold");
        }

        @Test
        @DisplayName("the catalogue copies what it was given, down to the per-subsystem lists")
        void catalogueIsIndependentOfItsInputs() {
            // Clearing the outer map is the easy half, and it was all the first version of this test
            // did — Map.copyOf is shallow, so each group list was still the caller's to empty.
            List<String> mutableGroup = new java.util.ArrayList<>(List.of("a.Alpha"));
            Map<String, List<String>> mutable = new java.util.HashMap<>(Map.of("x", mutableGroup));
            List<String> mutableCold = new java.util.ArrayList<>(List.of("c.Cold"));
            JfrEventCatalogue copied = new JfrEventCatalogue(JfrEventCatalogueTest.class, mutable, mutableCold);

            mutableGroup.clear();
            mutable.clear();
            mutableCold.clear();

            assertThat(copied.hotPathFor("x")).containsExactly("a.Alpha");
            assertThat(copied.allHotPath()).containsExactly("a.Alpha");
            assertThat(copied.deliberatelyCold()).containsExactly("c.Cold");
        }
    }

    /** A stand-in for an event class: touching it is what the warm-up is supposed to do. */
    static final class Probe {

        static {
            PROBE_INITIALISED.set(true);
        }

        private Probe() {
            // Never instantiated — the static initialiser is the whole subject.
        }
    }
}
