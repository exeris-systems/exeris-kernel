/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.spi.diagnostics;

/**
 * SPI: read-only, out-of-process-friendly introspection of kernel <em>state</em>.
 *
 * <p>This is the single public contract for reading what the kernel is composed of at runtime —
 * the discovered providers, the bootstrap DAG, and per-subsystem detail. It exists so external
 * consumers (the AI bridge, future CLIs, Studio remote-introspection)
 * stop reinventing a fragile join over {@code SubsystemOrchestrator} / provider internals, which live in
 * {@code exeris-kernel-core} and would break The Wall (ADR-006) and drift on every refactor.
 *
 * <p>It answers "what is composed right now?" with a <em>snapshot</em>, and carries <b>no</b> event /
 * tail / subscribe / watch surface: live event streaming stays on the JFR side (Community) and on the
 * Glass-Box binary stream over {@code exeris-telemetry-spec} (Enterprise). See ADR-039 for the open-core
 * observability boundary.
 *
 * <p>Each method captures its own {@link java.time.Instant} {@code capturedAt}, so a
 * {@link #listProviders()} + {@link #getBootstrapDag()} pair may straddle a state transition; the SPI
 * introduces no kernel-side locking to close that window (ADR-033 Obligation 7).
 *
 * <p>An instance is obtained from a {@link KernelDiagnosticsProvider} loaded via
 * {@link java.util.ServiceLoader}.
 *
 * <p><b>Allocation:</b> allocates (one snapshot record per call, plus the defensive
 * {@code List.copyOf(...)} it holds and the {@code Instant.now()} it stamps) — permitted because the
 * whole surface is cold path (ADR-033 Obligation 2).
 * <p><b>Ownership:</b> the returned snapshots are immutable, defensively copied and owned by the
 * caller; nothing is released, closed or handed back to the kernel, and holding one does not pin any
 * kernel resource.
 *
 * @implSpec Every method returns a non-null snapshot stamped with {@link #SCHEMA_VERSION} and its own
 *           {@code capturedAt}. State that cannot be read in the calling context degrades to an empty
 *           list or {@link java.util.Optional#empty()} rather than throwing. An implementation must not
 *           add kernel-side locking to make a multi-call view atomic (Obligation 7), must not grow an
 *           event, tail, subscribe or watch surface (Obligation 10), and must return the record types of
 *           this package unchanged — an overlay populates additional fields, it never forks the shapes
 *           (Obligation 6).
 * @apiNote  Cold path only: do not call these methods from a request hot path. Allocation is permitted
 *           on every call and the expected frequency is "per minute, not per request" (ADR-033
 *           Obligation 2). A consumer that needs a consistent multi-snapshot view reconstructs it from
 *           JFR event history rather than from a burst of calls here.
 * @implNote Community ships a {@code priority() == 0} provider; the Enterprise overlay
 *           ({@code priority() == 100}) returns the <em>same</em> record types with additional
 *           Enterprise-only fields populated where useful.
 * @since 0.9
 */
public interface KernelDiagnostics {

    /**
     * Current JSON wire-schema version of every snapshot record produced by this SPI.
     *
     * <p>Evolution is <b>append-only</b> within major version {@code 1.x}: adding a field is a minor
     * bump ({@code "1.1"}); removing or repurposing a field requires {@code "2.0"} plus a deprecation
     * window (ADR-033 Obligation 5).
     */
    String SCHEMA_VERSION = "1.0";

    /**
     * Names which provider implementation is serving each kernel SPI in this runtime: one
     * {@link ProviderDescriptor} per discovered provider, carrying the SPI domain it serves and the
     * priority it was discovered at.
     *
     * @return non-null immutable snapshot; {@code providers} may be empty before bootstrap completes
     */
    ProvidersSnapshot listProviders();

    /**
     * Exposes the resolved bootstrap topology: one {@link DagNode} per subsystem, carrying its phase,
     * its declared {@code dependsOn} edges and whether it is currently running.
     *
     * @return non-null immutable snapshot; {@code nodes} may be empty before bootstrap completes
     */
    BootstrapDagSnapshot getBootstrapDag();

    /**
     * Detail for a single subsystem, looked up by its free-form name.
     *
     * <p>The name matches {@code SubsystemOrchestrator.subsystem(String)} 1:1. Today's exhaustive set
     * (Javadoc only — promotion to a closed enum is deferred to 1.0 GA, ADR-033 Obligation 8):
     * {@code memory}, {@code crypto}, {@code persistence}, {@code graph}, {@code transport},
     * {@code events}, {@code flow}, {@code http}, {@code security}.
     *
     * @param name subsystem name; never {@code null}
     * @return non-null snapshot whose {@code subsystem} is empty when no subsystem with that name exists
     */
    SubsystemSnapshot describeSubsystem(String name);

    /**
     * Read-only snapshot of the JVM and container environment the kernel runs in — GC, heap geometry,
     * resolved processor count, cgroup-v2 CPU / memory / cpuset limits, and best-effort large-pages /
     * THP / CDS / AOT state.
     *
     * <p>Strictly observational (no tuning recommendations — those are the Enterprise advisor's surface
     * per ADR-008). The cold-path and best-effort-atomicity discipline of the rest of this surface
     * applies unchanged.
     *
     * @return non-null ergonomics snapshot; absent data is reported as {@link java.util.Optional#empty()}
     * @implSpec The default implementation reads nothing from the environment and returns
     *           {@link RuntimeErgonomicsSnapshot#unknown()} — a valid, non-null snapshot with every
     *           environment-sensitive field {@link java.util.Optional#empty()} — so a
     *           {@link KernelDiagnosticsProvider} written against the surface without this method stays
     *           binary-compatible (ADR-033 Obligation 5). An implementation that overrides it reports a
     *           value it cannot determine as {@link java.util.Optional#empty()}, never as a sentinel or
     *           {@code null}.
     * @implNote The Community and Enterprise providers override it with real
     *           {@code java.lang.management} plus procfs / cgroupfs reads.
     */
    default RuntimeErgonomicsSnapshot getJvmErgonomics() {
        return RuntimeErgonomicsSnapshot.unknown();
    }
}
