/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.community.diagnostics;

import eu.exeris.kernel.spi.bootstrap.Subsystem;
import eu.exeris.kernel.spi.context.KernelProviders;
import eu.exeris.kernel.spi.crypto.KernelCryptoProvider;
import eu.exeris.kernel.spi.diagnostics.BootstrapDagSnapshot;
import eu.exeris.kernel.spi.diagnostics.CapabilityDescriptor;
import eu.exeris.kernel.spi.diagnostics.CompositionSnapshot;
import eu.exeris.kernel.spi.diagnostics.DagNode;
import eu.exeris.kernel.spi.diagnostics.KernelDiagnostics;
import eu.exeris.kernel.spi.diagnostics.ProviderDescriptor;
import eu.exeris.kernel.spi.diagnostics.ProvidersSnapshot;
import eu.exeris.kernel.spi.diagnostics.SubsystemDescriptor;
import eu.exeris.kernel.spi.diagnostics.SubsystemSnapshot;
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
import java.util.Objects;
import java.util.Optional;
import java.util.ServiceLoader;
import java.util.function.Function;
import java.util.function.ToIntFunction;

/**
 * Community {@link KernelDiagnostics}.
 *
 * <p><b>Subsystem state</b> ({@link #listCapabilities()}, {@link #getBootstrapDag()},
 * {@link #describeSubsystem(String)}) is read from the {@link KernelProviders#SUBSYSTEMS}
 * {@link ScopedValue} slot — bound by the bootstrap, in inspect mode <em>before</em>
 * {@code initialize()} (static composition; {@code isRunning() == false}). <b>Provider inventory</b>
 * ({@link #listProviders()}) is resolved by {@link ServiceLoader} discovery (highest
 * {@code priority()} per SPI), so it works with no kernel scope and no infrastructure. Never reaches
 * into {@code exeris-kernel-core} (The Wall, ADR-006 / ADR-039).
 *
 * <p>All four methods are cold-path: each captures its own {@code capturedAt} and allocates fresh
 * records (ADR-033 Obligations 2 &amp; 7). When read outside a bound kernel scope the subsystem slot is
 * unbound and those snapshots are returned empty rather than throwing.
 *
 * @since 0.9.0
 */
final class CommunityKernelDiagnostics implements KernelDiagnostics {

    @Override
    public ProvidersSnapshot listProviders() {
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

    @Override
    public CompositionSnapshot listCapabilities() {
        List<CapabilityDescriptor> capabilities = new ArrayList<>();
        for (Subsystem subsystem : subsystems()) {
            capabilities.add(new CapabilityDescriptor(
                    subsystem.name(),
                    List.of(subsystem.name()),
                    subsystem.dependsOn(),
                    subsystem.isOptional()));
        }
        return CompositionSnapshot.capture(capabilities);
    }

    @Override
    public BootstrapDagSnapshot getBootstrapDag() {
        List<DagNode> nodes = new ArrayList<>();
        for (Subsystem subsystem : subsystems()) {
            nodes.add(toDagNode(subsystem));
        }
        return BootstrapDagSnapshot.capture(nodes);
    }

    @Override
    public SubsystemSnapshot describeSubsystem(String name) {
        Objects.requireNonNull(name, "name");
        Optional<SubsystemDescriptor> detail = subsystems().stream()
                .filter(s -> name.equals(s.name()))
                .findFirst()
                .map(CommunityKernelDiagnostics::toDescriptor);
        return SubsystemSnapshot.capture(name, detail);
    }

    private static List<Subsystem> subsystems() {
        return KernelProviders.SUBSYSTEMS.isBound()
                ? KernelProviders.SUBSYSTEMS.get()
                : List.of();
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

    private static DagNode toDagNode(Subsystem subsystem) {
        return new DagNode(
                subsystem.name(),
                subsystem.phase().name(),
                subsystem.dependsOn(),
                subsystem.isRunning(),
                subsystem.isOptional());
    }

    private static SubsystemDescriptor toDescriptor(Subsystem subsystem) {
        return new SubsystemDescriptor(
                subsystem.name(),
                subsystem.phase().name(),
                subsystem.dependsOn(),
                subsystem.isRunning(),
                subsystem.isOptional());
    }
}
