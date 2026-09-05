/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.spi.diagnostics;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * Immutable snapshot returned by {@link KernelDiagnostics#getBootstrapDag()}.
 *
 * <p>The DAG is given as a node list; each {@link DagNode} carries its own {@code dependsOn} edges, in
 * topological order where the orchestrator provides one.
 *
 * @param schemaVersion the wire-schema version (see {@link KernelDiagnostics#SCHEMA_VERSION})
 * @param capturedAt    the instant this snapshot was captured (best-effort, per-call)
 * @param nodes         the DAG nodes; defensively copied, never {@code null}
 * @since 0.9
 */
public record BootstrapDagSnapshot(
        String schemaVersion,
        Instant capturedAt,
        List<DagNode> nodes) {

    public BootstrapDagSnapshot {
        Objects.requireNonNull(schemaVersion, "schemaVersion");
        Objects.requireNonNull(capturedAt, "capturedAt");
        nodes = List.copyOf(nodes);
    }

    /**
     * Captures a snapshot now, stamping the current {@link KernelDiagnostics#SCHEMA_VERSION}.
     */
    public static BootstrapDagSnapshot capture(List<DagNode> nodes) {
        return new BootstrapDagSnapshot(KernelDiagnostics.SCHEMA_VERSION, Instant.now(), nodes);
    }
}
