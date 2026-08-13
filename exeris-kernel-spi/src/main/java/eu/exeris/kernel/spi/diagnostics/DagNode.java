/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
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
 * @since 0.9.0
 */
public value record DagNode(
        String name,
        String phase,
        List<String> dependsOn,
        boolean running,
        boolean optional) {

    public DagNode {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(phase, "phase");
        dependsOn = List.copyOf(dependsOn);
    }
}
