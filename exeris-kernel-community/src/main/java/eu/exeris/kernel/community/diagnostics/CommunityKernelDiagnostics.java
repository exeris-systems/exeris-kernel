/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
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
 * <p><b>Audit.</b> Each method emits its JFR audit event as its first statement, before any work
 * (ADR-033 Obligation 8, "on call"), so a call that throws is audited exactly like one that returns.
 *
 * <p>All four methods are cold-path: each captures its own {@code capturedAt} and allocates fresh
 * records (ADR-033 Obligations 2 &amp; 7). When read outside a bound kernel scope the subsystem slot is
 * unbound and those snapshots are returned empty rather than throwing.
 *
 * @since 0.9
 */
final class CommunityKernelDiagnostics implements KernelDiagnostics {

    /**
     * Emits a JFR audit event with code {@code EX-DIAG-1001}, then delegates to
     * {@link CommunityProviderInventory#snapshot()}.
     *
     * @return the provider inventory snapshot
     */
    @Override
    public ProvidersSnapshot listProviders() {
        CommunityKernelDiagnosticsEvent.emit(KernelErrorCodes.EX_DIAG_1001, "listProviders");
        return CommunityProviderInventory.snapshot();
    }

    /**
     * Emits a JFR audit event with code {@code EX-DIAG-1003}, then builds one {@link DagNode} per
     * subsystem currently bound to {@link KernelProviders#SUBSYSTEMS} — an empty snapshot when that
     * scope is unbound.
     *
     * @return the bootstrap DAG snapshot
     */
    @Override
    public BootstrapDagSnapshot getBootstrapDag() {
        CommunityKernelDiagnosticsEvent.emit(KernelErrorCodes.EX_DIAG_1003, "getBootstrapDag");
        List<DagNode> nodes = new ArrayList<>();
        for (Subsystem subsystem : subsystems()) {
            nodes.add(toDagNode(subsystem));
        }
        return BootstrapDagSnapshot.capture(nodes);
    }

    /**
     * Emits a JFR audit event with code {@code EX-DIAG-1004}, then looks up {@code name} among the
     * subsystems currently bound to {@link KernelProviders#SUBSYSTEMS} — no match when that scope is
     * unbound. The event precedes the argument check, so a call with a {@code null} name is audited
     * like any other.
     *
     * @param name subsystem name to look up
     * @return a snapshot whose subsystem detail is empty when no subsystem named {@code name} is found
     * @throws NullPointerException if {@code name} is {@code null}
     */
    @Override
    public SubsystemSnapshot describeSubsystem(String name) {
        CommunityKernelDiagnosticsEvent.emit(KernelErrorCodes.EX_DIAG_1004, "describeSubsystem");
        Objects.requireNonNull(name, "name");
        Optional<SubsystemDescriptor> detail = subsystems().stream()
                .filter(s -> name.equals(s.name()))
                .findFirst()
                .map(CommunityKernelDiagnostics::toDescriptor);
        return SubsystemSnapshot.capture(name, detail);
    }

    /**
     * Emits a JFR audit event with code {@code EX-DIAG-1005}, then delegates to
     * {@link CommunityRuntimeErgonomics#capture()}.
     *
     * @return the JVM and container ergonomics snapshot
     */
    @Override
    public RuntimeErgonomicsSnapshot getJvmErgonomics() {
        CommunityKernelDiagnosticsEvent.emit(KernelErrorCodes.EX_DIAG_1005, "getJvmErgonomics");
        return CommunityRuntimeErgonomics.capture();
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
