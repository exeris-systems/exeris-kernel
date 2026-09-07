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

    /**
     * Rejects a {@code null} {@code schemaVersion} or {@code capturedAt} and replaces {@code providers}
     * with an unmodifiable copy, so a published snapshot cannot be mutated through the list the producer
     * passed in.
     *
     * @throws NullPointerException if {@code schemaVersion}, {@code capturedAt} or {@code providers} is
     *                              {@code null}, or if {@code providers} contains a {@code null} element
     */
    public ProvidersSnapshot {
        Objects.requireNonNull(schemaVersion, "schemaVersion");
        Objects.requireNonNull(capturedAt, "capturedAt");
        providers = List.copyOf(providers);
    }

    /**
     * Wraps a provider inventory in a snapshot stamped with {@link KernelDiagnostics#SCHEMA_VERSION} and
     * the instant of this call, which is how a {@link KernelDiagnostics} implementation publishes
     * {@link KernelDiagnostics#listProviders()}.
     *
     * @param providers the discovered providers; copied defensively, so later mutation of the argument
     *                  is not visible through the snapshot. May be empty when nothing has been
     *                  discovered yet, but must not be {@code null} or contain {@code null}
     * @return a new snapshot carrying an unmodifiable copy of {@code providers}, the current schema
     *         version and a {@code capturedAt} taken at this call
     * @throws NullPointerException if {@code providers} is {@code null} or contains a {@code null}
     *                              element
     */
    public static ProvidersSnapshot capture(List<ProviderDescriptor> providers) {
        return new ProvidersSnapshot(KernelDiagnostics.SCHEMA_VERSION, Instant.now(), providers);
    }
}
