/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.core.bootstrap;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Comparator;
import java.util.Optional;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * L1 Contract: {@link BootstrapProviderSelector} ranking + availability filtering +
 * deterministic tie-break.
 *
 * <h2>Scope</h2>
 * <ul>
 *   <li>Higher comparator rank wins.</li>
 *   <li>Availability filter excludes providers BEFORE ranking — an unavailable
 *       higher-priority provider must NOT shadow an available lower-priority one
 *       (TCK-061 contract).</li>
 *   <li>Tie-break is by implementation class name (deterministic across JVMs).</li>
 *   <li>Empty selection returns {@link Optional#empty()}.</li>
 * </ul>
 *
 * <p>The test exercises the package-private {@code selectFrom(Stream, ...)} core to
 * decouple the contract from {@code ServiceLoader} discovery.</p>
 *
 * @since 0.7.0
 */
@DisplayName("L1: BootstrapProviderSelector — rank + availability filter + deterministic tie-break")
class BootstrapProviderSelectorTest {

    private interface FakeProvider {
        int priority();
        boolean isAvailable();
    }

    private static final class HighPriorityUnavailable implements FakeProvider {
        @Override public int priority()      { return 100; }
        @Override public boolean isAvailable() { return false; }
    }

    private static final class LowPriorityAvailable implements FakeProvider {
        @Override public int priority()      { return 10; }
        @Override public boolean isAvailable() { return true; }
    }

    private static final class HighPriorityAvailableA implements FakeProvider {
        @Override public int priority()      { return 50; }
        @Override public boolean isAvailable() { return true; }
    }

    private static final class HighPriorityAvailableB implements FakeProvider {
        @Override public int priority()      { return 50; }
        @Override public boolean isAvailable() { return true; }
    }

    private static final Comparator<FakeProvider> BY_PRIORITY =
            Comparator.comparingInt(FakeProvider::priority);

    @Nested
    @DisplayName("Availability filter (TCK-061): unavailable higher-priority provider must not shadow available lower-priority one")
    class AvailabilityFilterContract {

        @Test
        @DisplayName("Filter excludes the high-priority unavailable provider; low-priority available wins")
        void filterPicksAvailableLowerPriority() {
            FakeProvider winner = BootstrapProviderSelector.selectFrom(
                    Stream.of(new HighPriorityUnavailable(), new LowPriorityAvailable()),
                    BY_PRIORITY,
                    FakeProvider::isAvailable
            ).orElseThrow();
            assertThat(winner).isInstanceOf(LowPriorityAvailable.class);
        }

        @Test
        @DisplayName("Without the filter, the high-priority unavailable provider would have won — confirms the filter changes the outcome")
        void withoutFilterUnavailableProviderWins() {
            FakeProvider winner = BootstrapProviderSelector.selectFrom(
                    Stream.of(new HighPriorityUnavailable(), new LowPriorityAvailable()),
                    BY_PRIORITY,
                    p -> true
            ).orElseThrow();
            assertThat(winner).isInstanceOf(HighPriorityUnavailable.class);
        }

        @Test
        @DisplayName("All providers unavailable -> empty Optional")
        void allUnavailableYieldsEmpty() {
            Optional<FakeProvider> result = BootstrapProviderSelector.selectFrom(
                    Stream.of(new HighPriorityUnavailable()),
                    BY_PRIORITY,
                    FakeProvider::isAvailable
            );
            assertThat(result).isEmpty();
        }
    }

    @Nested
    @DisplayName("Deterministic tie-break by class name")
    class TieBreakContract {

        @Test
        @DisplayName("Two providers with equal priority -> winner is deterministic across runs (alphabetically last by class name wins under max())")
        void tieBreakByClassName() {
            FakeProvider a = new HighPriorityAvailableA();
            FakeProvider b = new HighPriorityAvailableB();

            FakeProvider winner1 = BootstrapProviderSelector.selectFrom(
                    Stream.of(a, b), BY_PRIORITY, p -> true).orElseThrow();
            FakeProvider winner2 = BootstrapProviderSelector.selectFrom(
                    Stream.of(b, a), BY_PRIORITY, p -> true).orElseThrow();
            // Stream.max with a comparator returns the maximum; alphabetical tiebreak
            // means HighPriorityAvailableB > HighPriorityAvailableA, so B wins regardless of input order.
            assertThat(winner1).isInstanceOf(HighPriorityAvailableB.class);
            assertThat(winner2).isInstanceOf(HighPriorityAvailableB.class);
        }
    }

    @Nested
    @DisplayName("Empty input + null guards")
    class GuardContract {

        @Test
        @DisplayName("Empty stream -> empty Optional")
        void emptyStream() {
            Optional<FakeProvider> result = BootstrapProviderSelector.selectFrom(
                    Stream.empty(), BY_PRIORITY, p -> true);
            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("Null providers stream rejected")
        void nullProvidersRejected() {
            assertThatThrownBy(() ->
                    BootstrapProviderSelector.selectFrom(null, BY_PRIORITY, p -> true))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("providers");
        }

        @Test
        @DisplayName("Null comparator rejected")
        void nullComparatorRejected() {
            assertThatThrownBy(() ->
                    BootstrapProviderSelector.selectFrom(Stream.empty(), null, p -> true))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("comparator");
        }

        @Test
        @DisplayName("Null availability filter rejected")
        void nullFilterRejected() {
            assertThatThrownBy(() ->
                    BootstrapProviderSelector.selectFrom(Stream.empty(), BY_PRIORITY, null))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("availabilityFilter");
        }
    }
}
