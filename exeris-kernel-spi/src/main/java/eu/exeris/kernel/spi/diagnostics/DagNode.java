/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.spi.diagnostics;

import java.util.List;
import java.util.Objects;

/**
 * Immutable node in the bootstrap dependency DAG. Edges are encoded as {@code dependsOn} names.
 *
 * @param name      the subsystem name (the DAG node key)
 * @param phase     the bootstrap phase name (e.g. {@code "FOUNDATION"}, {@code "SERVICES"}, {@code "RUNTIME"})
 * @param dependsOn names of subsystems this node depends on; defensively copied, never {@code null}
 * @param running   whether the subsystem is currently running
 * @param optional  whether the subsystem is optional (skippable under a DEGRADE failure policy)
 * @since 0.9
 */
public record DagNode(
        String name,
        String phase,
        List<String> dependsOn,
        boolean running,
        boolean optional) {

    /**
     * Rejects a {@code null} {@code name} or {@code phase} and replaces {@code dependsOn} with an
     * unmodifiable copy, so a node published into a snapshot cannot be mutated through the list the
     * caller passed in.
     *
     * @throws NullPointerException if {@code name}, {@code phase} or {@code dependsOn} is {@code null},
     *                              or if {@code dependsOn} contains a {@code null} element
     */
    public DagNode {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(phase, "phase");
        dependsOn = List.copyOf(dependsOn);
    }
}
