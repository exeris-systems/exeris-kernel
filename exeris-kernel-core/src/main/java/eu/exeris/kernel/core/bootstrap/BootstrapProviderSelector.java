/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.core.bootstrap;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.ServiceLoader;
import java.util.function.Predicate;
import java.util.function.ToIntFunction;

/**
 * Internal core utility for deterministic ServiceLoader-based provider selection.
 *
 * <p>Providers are ranked by the supplied comparator. When two providers compare as equal,
 * selection is deterministically resolved by provider implementation class name.
 *
 * <h2>Availability Filtering</h2>
 * <p>SPI types that expose a platform-availability predicate (currently
 * {@code TransportProvider.isAvailable()}) MUST use the
 * {@link #loadHighestPriority(Class, Comparator, Predicate)} overload and pass the
 * predicate explicitly. The selector applies the filter before ranking, so an
 * unavailable higher-priority provider does not shadow an available lower-priority
 * one. SPI types that do not declare an availability predicate use the unfiltered
 * {@link #loadHighestPriority(Class, Comparator)} method.</p>
 */
public final class BootstrapProviderSelector {

    private BootstrapProviderSelector() {
        // utility -- no instances
    }

    /**
     * Loads the highest-priority provider via {@link ServiceLoader}, ranked by
     * {@code comparator}. No availability filtering is applied. Use
     * {@link #loadHighestPriority(Class, Comparator, Predicate)} for SPI types that
     * declare a platform-availability predicate.
     *
     * @param providerType SPI type loaded via {@code ServiceLoader}
     * @param comparator   ranking comparator (higher rank wins)
     * @param <T>          provider type
     * @return highest-ranked provider, or empty if none is registered
     */
    public static <T> Optional<T> loadHighestPriority(Class<T> providerType,
                                                      Comparator<? super T> comparator) {
        return loadHighestPriority(providerType, comparator, anyProvider -> true);
    }

    /**
     * Loads the highest-priority <em>available</em> provider via {@link ServiceLoader}.
     *
     * <p>The {@code availabilityFilter} is applied before ranking; only providers for
     * which the predicate returns {@code true} participate in the selection. When two
     * available providers compare as equal under {@code comparator}, the tie is broken
     * deterministically by provider implementation class name.</p>
     *
     * @param providerType        SPI type loaded via {@code ServiceLoader}
     * @param comparator          ranking comparator (higher rank wins)
     * @param availabilityFilter  predicate that gates which providers can be selected;
     *                            typically a method reference such as
     *                            {@code TransportProvider::isAvailable}
     * @param <T>                 provider type
     * @return highest-ranked available provider, or empty if none qualify
     */
    public static <T> Optional<T> loadHighestPriority(Class<T> providerType,
                                                      Comparator<? super T> comparator,
                                                      Predicate<? super T> availabilityFilter) {
        Objects.requireNonNull(providerType, "providerType must not be null");
        return selectFrom(
                ServiceLoader.load(providerType)
                        .stream()
                        .map(ServiceLoader.Provider::get),
                comparator,
                availabilityFilter);
    }

    /**
     * Selects the highest-priority provider among {@code candidates}; ties break by implementation class
     * name. This is the selection {@link #loadHighestPriority(Class, Comparator)} applies to what
     * {@link ServiceLoader} discovers, exposed for callers that hold the candidates already.
     *
     * @param candidates providers to choose between; must not be {@code null}
     * @param priority   reads a provider's priority; must not be {@code null}
     * @param <T>        provider type
     * @return the selected provider, or empty when {@code candidates} is empty
     */
    public static <T> Optional<T> selectHighestPriority(List<T> candidates,
                                                        ToIntFunction<? super T> priority) {
        Objects.requireNonNull(candidates, "candidates must not be null");
        Objects.requireNonNull(priority, "priority must not be null");
        return selectFrom(candidates.stream(), Comparator.comparingInt(priority), any -> true);
    }

    /**
     * Package-private selection core. Splits the {@code ServiceLoader} discovery from the
     * filter+rank logic so tests can drive selection from any provider stream without
     * registering {@code META-INF/services} fixtures.
     */
    /* default */ static <T> Optional<T> selectFrom(java.util.stream.Stream<T> providers,
                                       Comparator<? super T> comparator,
                                       Predicate<? super T> availabilityFilter) {
        Objects.requireNonNull(providers, "providers must not be null");
        Objects.requireNonNull(comparator, "comparator must not be null");
        Objects.requireNonNull(availabilityFilter, "availabilityFilter must not be null");

        Comparator<T> deterministicComparator = (left, right) -> {
            int compared = comparator.compare(left, right);
            if (compared != 0) {
                return compared;
            }
            return left.getClass().getName().compareTo(right.getClass().getName());
        };

        return providers
                .filter(availabilityFilter)
                .max(deterministicComparator);
    }
}