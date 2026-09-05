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
 * <h2>State, not events (ADR-033 Obligation 10)</h2>
 * <p>This SPI answers "what is composed right now?" with a <em>snapshot</em>. It deliberately exposes
 * <b>no</b> event / tail / subscribe / watch surface: live event streaming stays on the JFR side
 * (Community) and the Glass-Box binary stream over {@code exeris-telemetry-spec} (Enterprise). See
 * ADR-039 for the open-core observability boundary.
 *
 * <h2>Cold-path discipline (ADR-033 Obligation 2)</h2>
 * <p>These methods MUST NOT be invoked from a request hot path. Allocation is permitted on each call
 * (record instantiation, defensive {@code List.copyOf(...)}, {@code Instant.now()}); the expected call
 * frequency is "per minute, not per request."
 *
 * <h2>Snapshot atomicity is best-effort (ADR-033 Obligation 7)</h2>
 * <p>Each method captures its own {@link java.time.Instant} {@code capturedAt}. A
 * {@link #listProviders()} + {@link #getBootstrapDag()} pair MAY straddle a state transition; the SPI
 * introduces no kernel-side locking to "fix" this. Consumers needing a consistent multi-snapshot view
 * reconstruct it from JFR event history.
 *
 * <h2>Discovery &amp; open-core overlay</h2>
 * <p>Obtained from a {@link KernelDiagnosticsProvider} loaded via
 * {@link java.util.ServiceLoader}. Community ships a {@code priority() == 0} provider; the Enterprise
 * overlay ({@code priority() == 100}) returns the <em>same</em> record types with additional
 * Enterprise-only fields populated where useful — never a fork of the shapes (ADR-033 Obligation 6).
 *
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
     * Snapshot of the kernel providers discovered/active in this runtime.
     *
     * @return non-null immutable snapshot; {@code providers} may be empty before bootstrap completes
     */
    ProvidersSnapshot listProviders();

    /**
     * Snapshot of the bootstrap dependency DAG (nodes + their declared dependencies).
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
     * per ADR-008). Same cold-path and best-effort-atomicity discipline as the other three methods.
     *
     * <p>This method was added after the initial four-method surface; per ADR-033 Obligation 5 adding a
     * method to the Java interface is a binary-breaking change for {@link KernelDiagnosticsProvider}
     * implementations, so it ships as a {@code default} returning {@link RuntimeErgonomicsSnapshot#unknown()}
     * (every environment-sensitive field {@link java.util.Optional#empty()}). A provider that does not
     * override it stays binary-compatible; the Community and Enterprise providers override it with real
     * {@code java.lang.management} + procfs/cgroupfs reads.
     *
     * @return non-null ergonomics snapshot; absent data is reported as {@link java.util.Optional#empty()}
     */
    default RuntimeErgonomicsSnapshot getJvmErgonomics() {
        return RuntimeErgonomicsSnapshot.unknown();
    }
}
