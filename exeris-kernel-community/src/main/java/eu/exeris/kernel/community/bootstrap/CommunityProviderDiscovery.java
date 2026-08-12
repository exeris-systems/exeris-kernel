/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.community.bootstrap;

import java.util.Comparator;
import java.util.ServiceLoader;
import java.util.function.ToIntFunction;

/**
 * Picks one provider out of everything {@link ServiceLoader} finds.
 *
 * <p>Highest {@code priority()} wins; ties break on fully-qualified class name so two providers at the
 * same tier resolve identically on every JVM and every classpath ordering. That determinism is the
 * point — a boot that picked a different driver depending on jar order would be diagnosed as anything
 * but what it is.
 */
final class CommunityProviderDiscovery {

    private CommunityProviderDiscovery() {
    }

    /**
     * Returns the highest-priority provider of {@code contract}, or {@code null} when none is present.
     *
     * <p>Absence is a configuration, not a failure: the caller decides whether an unbound slot is
     * acceptable for its subsystem.
     *
     * @param contract the SPI type to discover
     * @param priority reads the provider's priority
     * @param <T>      the provider type
     * @return the selected provider, or {@code null}
     */
    /* default */ static <T> T highestPriority(Class<T> contract, ToIntFunction<T> priority) {
        return ServiceLoader.load(contract)
                .stream()
                .map(ServiceLoader.Provider::get)
                .max(Comparator.comparingInt(priority)
                        .thenComparing(provider -> provider.getClass().getName()))
                .orElse(null);
    }
}
