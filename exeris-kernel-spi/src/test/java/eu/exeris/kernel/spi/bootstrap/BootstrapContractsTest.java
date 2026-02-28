/*
 * Copyright (C) 2025-2026 Exeris. All rights reserved.
 *
 * This code is part of the Exeris Systems.
 * Distributed under the proprietary Exeris Software License.
 * Unauthorized copying or distribution is prohibited.
 */
package eu.exeris.kernel.spi.bootstrap;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * L0 Contract: {@link BootstrapPhase} ordinal/order stability + {@link BootstrapSelector}
 * Valhalla-readiness and factory-method correctness.
 *
 * <h2>BootstrapPhase — Kahn Topological Sort</h2>
 * <p>Phase ordinals AND {@link BootstrapPhase#order()} codes are used as indices in the
 * Kahn's algorithm topological sort table inside {@code SubsystemOrchestrator}. Any
 * reordering corrupts the startup sequence without any compile-time error.
 *
 * <h2>BootstrapSelector — Valhalla-Ready record</h2>
 * <p>Verifies structural equality, defensive copy of {@code requestedNames},
 * ALL/NONE/forNames factory semantics, and the {@code includes()} predicate.
 *
 * @since 0.5.0
 */
@DisplayName("L0: Bootstrap — BootstrapPhase ordinals + BootstrapSelector contracts")
class BootstrapContractsTest {

    // =========================================================================
    // BootstrapPhase ordinal and order stability
    // =========================================================================

    @Nested
    @DisplayName("BootstrapPhase — ordinal + order stability (Kahn sort table regression shield)")
    class BootstrapPhaseContract {

        @Test
        @DisplayName("FOUNDATION ordinal == 0 and order() == 0")
        void foundationIsFirst() {
            assertThat(BootstrapPhase.FOUNDATION.ordinal()).isZero();
            assertThat(BootstrapPhase.FOUNDATION.order()).isZero();
        }

        @Test
        @DisplayName("SERVICES ordinal == 1 and order() == 1")
        void servicesIsSecond() {
            assertThat(BootstrapPhase.SERVICES.ordinal()).isEqualTo(1);
            assertThat(BootstrapPhase.SERVICES.order()).isEqualTo(1);
        }

        @Test
        @DisplayName("RUNTIME ordinal == 2 and order() == 2")
        void runtimeIsThird() {
            assertThat(BootstrapPhase.RUNTIME.ordinal()).isEqualTo(2);
            assertThat(BootstrapPhase.RUNTIME.order()).isEqualTo(2);
        }

        @Test
        @DisplayName("Total BootstrapPhase count == 3 — new phase requires Kahn table review")
        void totalPhaseCount() {
            assertThat(BootstrapPhase.values()).hasSize(3);
        }

        @Test
        @DisplayName("isBefore(): FOUNDATION < SERVICES < RUNTIME")
        void isBeforeOrdering() {
            assertThat(BootstrapPhase.FOUNDATION.isBefore(BootstrapPhase.SERVICES)).isTrue();
            assertThat(BootstrapPhase.FOUNDATION.isBefore(BootstrapPhase.RUNTIME)).isTrue();
            assertThat(BootstrapPhase.SERVICES.isBefore(BootstrapPhase.RUNTIME)).isTrue();
        }

        @Test
        @DisplayName("isBefore() returns false when same or later phase")
        void isBeforeReturnsFalseForSameOrLater() {
            assertThat(BootstrapPhase.FOUNDATION.isBefore(BootstrapPhase.FOUNDATION)).isFalse();
            assertThat(BootstrapPhase.SERVICES.isBefore(BootstrapPhase.FOUNDATION)).isFalse();
            assertThat(BootstrapPhase.RUNTIME.isBefore(BootstrapPhase.SERVICES)).isFalse();
        }

        @Test
        @DisplayName("order() values are strictly increasing: 0 < 1 < 2")
        void orderIsStrictlyIncreasing() {
            BootstrapPhase[] phases = BootstrapPhase.values();
            for (int i = 1; i < phases.length; i++) {
                assertThat(phases[i].order())
                        .as("%s.order() must exceed %s.order()", phases[i], phases[i - 1])
                        .isGreaterThan(phases[i - 1].order());
            }
        }

        @Test
        @DisplayName("valueOf() resolves all canonical names")
        void valueOfResolvesAll() {
            assertThat(BootstrapPhase.valueOf("FOUNDATION")).isEqualTo(BootstrapPhase.FOUNDATION);
            assertThat(BootstrapPhase.valueOf("SERVICES")).isEqualTo(BootstrapPhase.SERVICES);
            assertThat(BootstrapPhase.valueOf("RUNTIME")).isEqualTo(BootstrapPhase.RUNTIME);
        }
    }

    // =========================================================================
    // BootstrapSelector — Valhalla-readiness and factory semantics
    // =========================================================================

    @Nested
    @DisplayName("BootstrapSelector — factory semantics + structural equality")
    class BootstrapSelectorContract {

        // --- all() ---

        @Test
        @DisplayName("all(): selectAll==true, requestedNames is empty")
        void allSelectorState() {
            BootstrapSelector sel = BootstrapSelector.all();
            assertThat(sel.selectAll()).isTrue();
            assertThat(sel.requestedNames()).isEmpty();
            assertThat(sel.isAll()).isTrue();
        }

        @Test
        @DisplayName("all(): includes() returns true for any name")
        void allIncludesEverything() {
            BootstrapSelector sel = BootstrapSelector.all();
            assertThat(sel.includes("memory")).isTrue();
            assertThat(sel.includes("persistence")).isTrue();
            assertThat(sel.includes("nonexistent-subsystem")).isTrue();
        }

        @Test
        @DisplayName("all(): toString() == 'ALL'")
        void allToString() {
            assertThat(BootstrapSelector.all()).hasToString("ALL");
        }

        // --- none() ---

        @Test
        @DisplayName("none(): selectAll==false, requestedNames is empty")
        void noneSelectorState() {
            BootstrapSelector sel = BootstrapSelector.none();
            assertThat(sel.selectAll()).isFalse();
            assertThat(sel.requestedNames()).isEmpty();
            assertThat(sel.isAll()).isFalse();
        }

        @Test
        @DisplayName("none(): includes() returns false for every name")
        void noneIncludesNothing() {
            BootstrapSelector sel = BootstrapSelector.none();
            assertThat(sel.includes("memory")).isFalse();
            assertThat(sel.includes("persistence")).isFalse();
        }

        // --- forNames() ---

        @Test
        @DisplayName("forNames('persistence'): selectAll==false, requestedNames contains 'persistence'")
        void forNamesSingleSubsystem() {
            BootstrapSelector sel = BootstrapSelector.forNames("persistence");
            assertThat(sel.selectAll()).isFalse();
            assertThat(sel.requestedNames()).containsExactly("persistence");
            assertThat(sel.includes("persistence")).isTrue();
            assertThat(sel.includes("memory")).isFalse();
        }

        @Test
        @DisplayName("forNames('events', 'flow'): includes both, excludes others")
        void forNamesMultipleSubsystems() {
            BootstrapSelector sel = BootstrapSelector.forNames("events", "flow");
            assertThat(sel.includes("events")).isTrue();
            assertThat(sel.includes("flow")).isTrue();
            assertThat(sel.includes("memory")).isFalse();
        }

        @Test
        @DisplayName("forNames() with empty array throws IllegalArgumentException")
        void forNamesEmptyThrows() {
            assertThatThrownBy(BootstrapSelector::forNames)
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("forNames() with null throws IllegalArgumentException or NullPointerException")
        void forNamesNullThrows() {
            assertThatThrownBy(() -> BootstrapSelector.forNames((String[]) null))
                    .isInstanceOfAny(IllegalArgumentException.class, NullPointerException.class);
        }

        // --- defensive copy of requestedNames ---

        @Test
        @DisplayName("requestedNames is immutable — external mutation is rejected")
        void requestedNamesIsImmutable() {
            BootstrapSelector sel = BootstrapSelector.forNames("persistence");
            var names = sel.requestedNames();
            assertThatThrownBy(() -> names.add("memory"))
                    .isInstanceOfAny(UnsupportedOperationException.class,
                            IllegalArgumentException.class);
        }

        // --- Valhalla: structural equals/hashCode ---

        @Test
        @DisplayName("Structural equals: two all() selectors are equal")
        void allSelectorsEqual() {
            assertThat(BootstrapSelector.all()).isEqualTo(BootstrapSelector.all());
        }

        @Test
        @DisplayName("Structural equals: two none() selectors are equal")
        void noneSelectorsEqual() {
            assertThat(BootstrapSelector.none()).isEqualTo(BootstrapSelector.none());
        }

        @Test
        @DisplayName("Structural equals: forNames same set → equal")
        void forNamesSameSetEqual() {
            BootstrapSelector a = BootstrapSelector.forNames("persistence", "events");
            BootstrapSelector b = BootstrapSelector.forNames("events", "persistence");
            assertThat(a).isEqualTo(b);
        }

        @Test
        @DisplayName("Structural hashCode: equal selectors have same hashCode")
        void equalSelectorsHaveSameHashCode() {
            assertThat(BootstrapSelector.all()).hasSameHashCodeAs(BootstrapSelector.all());
        }

        @Test
        @DisplayName("all() != none() — selectAll field participates in equals")
        void allNotEqualToNone() {
            assertThat(BootstrapSelector.all()).isNotEqualTo(BootstrapSelector.none());
        }
    }
}

