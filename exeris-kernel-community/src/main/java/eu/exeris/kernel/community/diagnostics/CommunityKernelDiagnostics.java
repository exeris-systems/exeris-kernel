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
import eu.exeris.kernel.spi.diagnostics.DagNode;
import eu.exeris.kernel.spi.diagnostics.KernelDiagnostics;
import eu.exeris.kernel.spi.diagnostics.ProvidersSnapshot;
import eu.exeris.kernel.spi.diagnostics.RuntimeErgonomicsSnapshot;
import eu.exeris.kernel.spi.diagnostics.SubsystemDescriptor;
import eu.exeris.kernel.spi.diagnostics.SubsystemSnapshot;
import eu.exeris.kernel.spi.exceptions.KernelErrorCodes;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Community {@link KernelDiagnostics}.
 *
 * <p><b>Subsystem state</b> ({@link #getBootstrapDag()}, {@link #describeSubsystem(String)}) is read
 * from the {@link KernelProviders#SUBSYSTEMS}
 * {@link ScopedValue} slot — bound by the bootstrap, in inspect mode <em>before</em>
 * {@code initialize()} (static composition; {@code isRunning() == false}). <b>Provider inventory</b>
 * ({@link #listProviders()}) is resolved by {@link CommunityProviderInventory} via
 * {@link java.util.ServiceLoader} discovery, so it works with no kernel scope and no infrastructure.
 * Never reaches into {@code exeris-kernel-core} (The Wall, ADR-006 / ADR-039).
 *
 * <p><b>JVM ergonomics</b> ({@link #getJvmErgonomics()}) reads {@code java.lang.management} plus the
 * Linux cgroup-v2 hierarchy / procfs via {@link CommunityRuntimeErgonomics}; absent data degrades to
 * {@code Optional.empty()} rather than throwing.
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
        ProvidersSnapshot snapshot = CommunityProviderInventory.snapshot();
        CommunityKernelDiagnosticsEvent.emit(KernelErrorCodes.EX_DIAG_1001, "listProviders");
        return snapshot;
    }

    @Override
    public BootstrapDagSnapshot getBootstrapDag() {
        List<DagNode> nodes = new ArrayList<>();
        for (Subsystem subsystem : subsystems()) {
            nodes.add(toDagNode(subsystem));
        }
        CommunityKernelDiagnosticsEvent.emit(KernelErrorCodes.EX_DIAG_1003, "getBootstrapDag");
        return BootstrapDagSnapshot.capture(nodes);
    }

    @Override
    public SubsystemSnapshot describeSubsystem(String name) {
        Objects.requireNonNull(name, "name");
        Optional<SubsystemDescriptor> detail = subsystems().stream()
                .filter(s -> name.equals(s.name()))
                .findFirst()
                .map(CommunityKernelDiagnostics::toDescriptor);
        CommunityKernelDiagnosticsEvent.emit(KernelErrorCodes.EX_DIAG_1004, "describeSubsystem");
        return SubsystemSnapshot.capture(name, detail);
    }

    @Override
    public RuntimeErgonomicsSnapshot getJvmErgonomics() {
        RuntimeErgonomicsSnapshot snapshot = CommunityRuntimeErgonomics.capture();
        CommunityKernelDiagnosticsEvent.emit(KernelErrorCodes.EX_DIAG_1005, "getJvmErgonomics");
        return snapshot;
    }

    private static List<Subsystem> subsystems() {
        return KernelProviders.SUBSYSTEMS.isBound()
                ? KernelProviders.SUBSYSTEMS.get()
                : List.of();
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
