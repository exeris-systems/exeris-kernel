/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.spi.diagnostics;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * Immutable snapshot returned by {@link KernelDiagnostics#describeSubsystem(String)}.
 *
 * <p>{@code subsystem} is {@link Optional#empty()} when no subsystem with the requested name exists
 * (or is not yet present in this runtime); {@code requestedName} always echoes the lookup key so the
 * response is self-describing even on a miss.
 *
 * @param schemaVersion the wire-schema version (see {@link KernelDiagnostics#SCHEMA_VERSION})
 * @param capturedAt    the instant this snapshot was captured (best-effort, per-call)
 * @param requestedName the name passed to {@link KernelDiagnostics#describeSubsystem(String)}
 * @param subsystem     the subsystem detail, or empty when not found
 * @since 0.9
 */
public record SubsystemSnapshot(
        String schemaVersion,
        Instant capturedAt,
        String requestedName,
        Optional<SubsystemDescriptor> subsystem) {

    /**
     * Rejects a {@code null} component: a miss is carried as {@link Optional#empty()} in
     * {@code subsystem}, never as a {@code null} component.
     *
     * @throws NullPointerException if {@code schemaVersion}, {@code capturedAt}, {@code requestedName}
     *                              or {@code subsystem} is {@code null}
     */
    public SubsystemSnapshot {
        Objects.requireNonNull(schemaVersion, "schemaVersion");
        Objects.requireNonNull(capturedAt, "capturedAt");
        Objects.requireNonNull(requestedName, "requestedName");
        Objects.requireNonNull(subsystem, "subsystem");
    }

    /**
     * Pairs a lookup key with its result in a snapshot stamped with
     * {@link KernelDiagnostics#SCHEMA_VERSION} and the instant of this call, which is how a
     * {@link KernelDiagnostics} implementation answers
     * {@link KernelDiagnostics#describeSubsystem(String)} — including on a miss.
     *
     * @param requestedName the name that was looked up; echoed back verbatim so the snapshot is
     *                      self-describing even when nothing matched. Must not be {@code null}
     * @param subsystem     the detail found for that name, or {@link Optional#empty()} when no subsystem
     *                      of that name exists in this runtime. Must not be {@code null}
     * @return a new snapshot carrying {@code requestedName}, {@code subsystem}, the current schema
     *         version and a {@code capturedAt} taken at this call
     * @throws NullPointerException if {@code requestedName} or {@code subsystem} is {@code null}
     */
    public static SubsystemSnapshot capture(String requestedName, Optional<SubsystemDescriptor> subsystem) {
        return new SubsystemSnapshot(KernelDiagnostics.SCHEMA_VERSION, Instant.now(), requestedName, subsystem);
    }
}
