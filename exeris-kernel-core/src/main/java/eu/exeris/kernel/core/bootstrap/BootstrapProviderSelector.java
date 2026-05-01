/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.core.bootstrap;

import java.util.Comparator;
import java.util.Objects;
import java.util.Optional;
import java.util.ServiceLoader;

/**
 * Internal core utility for deterministic ServiceLoader-based provider selection.
 *
 * <p>Providers are ranked by the supplied comparator. When two providers compare as equal,
 * selection is deterministically resolved by provider implementation class name.</p>
 */
public final class BootstrapProviderSelector {

    private BootstrapProviderSelector() {
        // utility -- no instances
    }

    public static <T> Optional<T> loadHighestPriority(Class<T> providerType,
                                                      Comparator<? super T> comparator) {
        Objects.requireNonNull(providerType, "providerType must not be null");
        Objects.requireNonNull(comparator, "comparator must not be null");

        Comparator<T> deterministicComparator = (left, right) -> {
            int compared = comparator.compare(left, right);
            if (compared != 0) {
                return compared;
            }
            return left.getClass().getName().compareTo(right.getClass().getName());
        };

        return ServiceLoader.load(providerType)
                .stream()
                .map(ServiceLoader.Provider::get)
                .max(deterministicComparator);
    }
}