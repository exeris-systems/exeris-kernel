/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.spi.diagnostics;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * Immutable snapshot returned by {@link KernelDiagnostics#listProviders()}.
 *
 * <p>{@code schemaVersion} is the first field by contract — it is the first key in the JSON wire form
 * (ADR-033 Obligation 5).
 *
 * @param schemaVersion the wire-schema version (see {@link KernelDiagnostics#SCHEMA_VERSION})
 * @param capturedAt    the instant this snapshot was captured (best-effort, per-call; ADR-033 Obligation 7)
 * @param providers     the discovered providers; defensively copied, never {@code null}
 * @since 0.9
 */
public record ProvidersSnapshot(
        String schemaVersion,
        Instant capturedAt,
        List<ProviderDescriptor> providers) {

    public ProvidersSnapshot {
        Objects.requireNonNull(schemaVersion, "schemaVersion");
        Objects.requireNonNull(capturedAt, "capturedAt");
        providers = List.copyOf(providers);
    }

    /**
     * Captures a snapshot now, stamping the current {@link KernelDiagnostics#SCHEMA_VERSION}.
     */
    public static ProvidersSnapshot capture(List<ProviderDescriptor> providers) {
        return new ProvidersSnapshot(KernelDiagnostics.SCHEMA_VERSION, Instant.now(), providers);
    }
}
