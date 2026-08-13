/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
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
 * @since 0.9.0
 */
public value record SubsystemSnapshot(
        String schemaVersion,
        Instant capturedAt,
        String requestedName,
        Optional<SubsystemDescriptor> subsystem) {

    public SubsystemSnapshot {
        Objects.requireNonNull(schemaVersion, "schemaVersion");
        Objects.requireNonNull(capturedAt, "capturedAt");
        Objects.requireNonNull(requestedName, "requestedName");
        Objects.requireNonNull(subsystem, "subsystem");
    }

    /**
     * Captures a snapshot now, stamping the current {@link KernelDiagnostics#SCHEMA_VERSION}.
     */
    public static SubsystemSnapshot capture(String requestedName, Optional<SubsystemDescriptor> subsystem) {
        return new SubsystemSnapshot(KernelDiagnostics.SCHEMA_VERSION, Instant.now(), requestedName, subsystem);
    }
}
