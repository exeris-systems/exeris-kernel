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
import java.util.List;
import java.util.Objects;

/**
 * Immutable snapshot returned by {@link KernelDiagnostics#listCapabilities()}.
 *
 * @param schemaVersion the wire-schema version (see {@link KernelDiagnostics#SCHEMA_VERSION})
 * @param capturedAt    the instant this snapshot was captured (best-effort, per-call)
 * @param capabilities  the resolved capabilities; defensively copied, never {@code null}
 * @since 0.9.0
 */
public record CompositionSnapshot(
        String schemaVersion,
        Instant capturedAt,
        List<CapabilityDescriptor> capabilities) {

    public CompositionSnapshot {
        Objects.requireNonNull(schemaVersion, "schemaVersion");
        Objects.requireNonNull(capturedAt, "capturedAt");
        capabilities = List.copyOf(capabilities);
    }

    /**
     * Captures a snapshot now, stamping the current {@link KernelDiagnostics#SCHEMA_VERSION}.
     */
    public static CompositionSnapshot capture(List<CapabilityDescriptor> capabilities) {
        return new CompositionSnapshot(KernelDiagnostics.SCHEMA_VERSION, Instant.now(), capabilities);
    }
}
