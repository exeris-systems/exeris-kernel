/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.diagnostics;

import eu.exeris.kernel.spi.crypto.KernelCryptoProvider;
import eu.exeris.kernel.spi.diagnostics.ProviderDescriptor;
import eu.exeris.kernel.spi.diagnostics.ProvidersSnapshot;
import eu.exeris.kernel.spi.events.EventProvider;
import eu.exeris.kernel.spi.flow.FlowProvider;
import eu.exeris.kernel.spi.graph.GraphProvider;
import eu.exeris.kernel.spi.memory.MemoryProvider;
import eu.exeris.kernel.spi.persistence.PersistenceProvider;
import eu.exeris.kernel.spi.security.SecurityProvider;
import eu.exeris.kernel.spi.telemetry.TelemetryProvider;
import eu.exeris.kernel.spi.transport.TransportProvider;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.ServiceLoader;
import java.util.function.Function;
import java.util.function.ToIntFunction;

/**
 * Resolves the active kernel provider inventory for {@code KernelDiagnostics.listProviders()} via
 * {@link ServiceLoader} discovery (highest {@code priority()} per SPI). Discovery-based, so it works with
 * no kernel scope and no infrastructure — never reaches into {@code exeris-kernel-core} (The Wall).
 *
 * @since 0.9.0
 */
final class CommunityProviderInventory {

    private CommunityProviderInventory() {
    }

    /* default */ static ProvidersSnapshot snapshot() {
        List<ProviderDescriptor> providers = new ArrayList<>();
        discover(providers, MemoryProvider.class, "memory",
                MemoryProvider::providerName, MemoryProvider::priority);
        discover(providers, KernelCryptoProvider.class, "crypto",
                KernelCryptoProvider::providerName, KernelCryptoProvider::priority);
        discover(providers, TelemetryProvider.class, "telemetry",
                TelemetryProvider::providerName, TelemetryProvider::priority);
        discover(providers, PersistenceProvider.class, "persistence",
                PersistenceProvider::providerName, PersistenceProvider::priority);
        discover(providers, EventProvider.class, "events",
                EventProvider::providerName, EventProvider::priority);
        discover(providers, FlowProvider.class, "flow",
                FlowProvider::providerName, FlowProvider::priority);
        discover(providers, TransportProvider.class, "transport",
                TransportProvider::providerName, TransportProvider::priority);
        discover(providers, GraphProvider.class, "graph",
                GraphProvider::providerName, GraphProvider::priority);
        discover(providers, SecurityProvider.class, "security",
                SecurityProvider::providerName, SecurityProvider::priority);
        return ProvidersSnapshot.capture(providers);
    }

    /** Discovers the highest-priority provider for one SPI via {@link ServiceLoader}, if any. */
    private static <T> void discover(List<ProviderDescriptor> out, Class<T> spi, String spiType,
                                     Function<T, String> name, ToIntFunction<T> priority) {
        ServiceLoader.load(spi).stream()
                .map(ServiceLoader.Provider::get)
                .max(Comparator.comparingInt(priority))
                .ifPresent(provider -> out.add(new ProviderDescriptor(
                        name.apply(provider), spiType, priority.applyAsInt(provider), Optional.empty())));
    }
}
