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
import eu.exeris.kernel.spi.diagnostics.BootstrapDagSnapshot;
import eu.exeris.kernel.spi.diagnostics.CapabilityDescriptor;
import eu.exeris.kernel.spi.diagnostics.CompositionSnapshot;
import eu.exeris.kernel.spi.diagnostics.DagNode;
import eu.exeris.kernel.spi.diagnostics.KernelDiagnostics;
import eu.exeris.kernel.spi.diagnostics.ProviderDescriptor;
import eu.exeris.kernel.spi.diagnostics.ProvidersSnapshot;
import eu.exeris.kernel.spi.diagnostics.SubsystemDescriptor;
import eu.exeris.kernel.spi.diagnostics.SubsystemSnapshot;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.ToIntFunction;

/**
 * Community {@link KernelDiagnostics}. Reads the running kernel's state from the
 * {@code KernelProviders} {@link ScopedValue} slots bound during bootstrap — never from
 * {@code exeris-kernel-core} internals (The Wall, ADR-006 / ADR-039).
 *
 * <p>All four methods are cold-path: each captures its own {@code capturedAt} and allocates fresh
 * records (ADR-033 Obligations 2 &amp; 7). When read outside a bound kernel scope the relevant slot is
 * unbound and the snapshot is returned empty rather than throwing.
 *
 * @since 0.9.0
 */
final class CommunityKernelDiagnostics implements KernelDiagnostics {

    @Override
    public ProvidersSnapshot listProviders() {
        List<ProviderDescriptor> providers = new ArrayList<>();
        addIfBound(providers, KernelProviders.MEMORY_PROVIDER, "memory",
                p -> p.providerName(), p -> p.priority());
        addIfBound(providers, KernelProviders.CRYPTO_PROVIDER, "crypto",
                p -> p.providerName(), p -> p.priority());
        addIfBound(providers, KernelProviders.TELEMETRY_PROVIDER, "telemetry",
                p -> p.providerName(), p -> p.priority());
        addIfBound(providers, KernelProviders.PERSISTENCE_PROVIDER, "persistence",
                p -> p.providerName(), p -> p.priority());
        addIfBound(providers, KernelProviders.EVENT_PROVIDER, "events",
                p -> p.providerName(), p -> p.priority());
        addIfBound(providers, KernelProviders.FLOW_PROVIDER, "flow",
                p -> p.providerName(), p -> p.priority());
        addIfBound(providers, KernelProviders.TRANSPORT_PROVIDER, "transport",
                p -> p.providerName(), p -> p.priority());
        addIfBound(providers, KernelProviders.GRAPH_PROVIDER, "graph",
                p -> p.providerName(), p -> p.priority());
        addIfBound(providers, KernelProviders.SECURITY_PROVIDER, "security",
                p -> p.providerName(), p -> p.priority());
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

    private static <T> void addIfBound(List<ProviderDescriptor> out, ScopedValue<T> slot,
                                       String spiType, Function<T, String> name, ToIntFunction<T> priority) {
        if (slot.isBound()) {
            T provider = slot.get();
            out.add(new ProviderDescriptor(
                    name.apply(provider), spiType, priority.applyAsInt(provider), Optional.empty()));
        }
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
