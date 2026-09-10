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

    /**
     * Rejects a {@code null} {@code schemaVersion} or {@code capturedAt} and replaces {@code nodes} with
     * an unmodifiable copy, so a published snapshot cannot be mutated through the list the producer
     * passed in.
     *
     * @throws NullPointerException if {@code schemaVersion}, {@code capturedAt} or {@code nodes} is
     *                              {@code null}, or if {@code nodes} contains a {@code null} element
     */
    public BootstrapDagSnapshot {
        Objects.requireNonNull(schemaVersion, "schemaVersion");
        Objects.requireNonNull(capturedAt, "capturedAt");
        nodes = List.copyOf(nodes);
    }

    /**
     * Wraps a DAG in a snapshot stamped with {@link KernelDiagnostics#SCHEMA_VERSION} and the instant of
     * this call, which is how a {@link KernelDiagnostics} implementation publishes bootstrap topology.
     *
     * @param nodes the DAG nodes to publish, in topological order where the caller has one; copied
     *              defensively, so later mutation of the argument is not visible through the snapshot.
     *              Must not be {@code null} and must not contain {@code null}
     * @return a new snapshot carrying an unmodifiable copy of {@code nodes}, the current schema version
     *         and a {@code capturedAt} taken at this call
     * @throws NullPointerException if {@code nodes} is {@code null} or contains a {@code null} element
     */
    public static BootstrapDagSnapshot capture(List<DagNode> nodes) {
        return new BootstrapDagSnapshot(KernelDiagnostics.SCHEMA_VERSION, Instant.now(), nodes);
    }
}
