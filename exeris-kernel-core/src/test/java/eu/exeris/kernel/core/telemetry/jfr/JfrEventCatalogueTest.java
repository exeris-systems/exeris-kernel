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
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

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
    private static final String NESTED = "eu.exeris.kernel.core.telemetry.jfr.NestedWarmupProbe$Inner";
    private static final String TOP_LEVEL = "eu.exeris.kernel.core.telemetry.jfr.TopLevelWarmupProbe";

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
        @DisplayName("a nested name also runs its enclosing initialiser, outermost first")
        void warmingANestedNameAlsoRunsItsEnclosingInitialiser() {
            // The gap this closes: JLS 12.4.1 says initialising Outer$Inner does not initialise
            // Outer, and for most of this kernel's nested events the emit helper is on Outer — so
            // warming only the inner class left the <clinit> the warm-up exists to move exactly
            // where it was, on the first emitting virtual thread.
            JfrEventCatalogue catalogue = new JfrEventCatalogue(
                    JfrEventCatalogueTest.class, Map.of("nested", List.of(NESTED)), List.of());

            assertThat(entriesFor("NestedWarmupProbe"))
                    .withFailMessage("the probe initialised before the warm-up ran; the assertion below would be vacuous")
                    .isEmpty();

            catalogue.warmHotPath("nested");

            // containsExactly, not containsExactlyInAnyOrder: the order is the claim. The first emit
            // would have initialised the holder and then reached the event class, and a warm-up that
            // runs them the other way round is not reproducing what it replaces.
            assertThat(entriesFor("NestedWarmupProbe"))
                    .containsExactly("NestedWarmupProbe", "NestedWarmupProbe$Inner");
        }

        @Test
        @DisplayName("a top-level name initialises that class and nothing else")
        void warmingATopLevelNameInitialisesOnlyThatClass() {
            JfrEventCatalogue catalogue = new JfrEventCatalogue(
                    JfrEventCatalogueTest.class, Map.of("flat", List.of(TOP_LEVEL)), List.of());

            catalogue.warmHotPath("flat");

            // getEnclosingClass() or getNestHost() in place of getDeclaringClass() would reach past
            // a top-level class in cases this one stands in for; so would splitting the name.
            assertThat(entriesFor("TopLevelWarmupProbe")).containsExactly("TopLevelWarmupProbe");
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

    @Nested
    @DisplayName("Construction")
    class Construction {

        @Test
        @DisplayName("a null argument is rejected where it is passed, naming the catalogue")
        void nullArgumentsAreRejected() {
            assertThatNullPointerException().isThrownBy(() ->
                    new JfrEventCatalogue(null, Map.of(), List.of()));
            assertThatNullPointerException().isThrownBy(() ->
                    new JfrEventCatalogue(JfrEventCatalogueTest.class, null, List.of()))
                    .withMessageContaining(JfrEventCatalogueTest.class.getName());
            assertThatNullPointerException().isThrownBy(() ->
                    new JfrEventCatalogue(JfrEventCatalogueTest.class, Map.of(), null))
                    .withMessageContaining(JfrEventCatalogueTest.class.getName());
        }

        @Test
        @DisplayName("a blank class name is rejected, naming the bucket it sits in")
        void blankNamesAreRejected() {
            assertThatIllegalArgumentException().isThrownBy(() -> new JfrEventCatalogue(
                    JfrEventCatalogueTest.class, Map.of("transport", List.of("  ")), List.of()))
                    .withMessageContaining("transport");
            assertThatIllegalArgumentException().isThrownBy(() -> new JfrEventCatalogue(
                    JfrEventCatalogueTest.class, Map.of(), List.of("")))
                    .withMessageContaining("the cold list");
        }

        @Test
        @DisplayName("a name in both buckets is rejected — the coverage guard cannot see that one")
        void aNameCannotBeBothWarmedAndCold() {
            // The guard matches the union of the two buckets against a module's declared event
            // classes, so a name counted twice still lands in that union and passes.
            assertThatIllegalArgumentException().isThrownBy(() -> new JfrEventCatalogue(
                    JfrEventCatalogueTest.class, Map.of("transport", List.of("a.Alpha")), List.of("a.Alpha")))
                    .withMessageContaining("a.Alpha");
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

    /**
     * The recorded initialisations belonging to one probe, in order.
     *
     * @param probe the probe's simple binary name
     * @return the matching entries
     */
    private static List<String> entriesFor(String probe) {
        return WarmupProbeLog.entries().stream().filter(e -> e.startsWith(probe)).toList();
    }
}
