/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.spi.diagnostics;

import java.util.List;
import java.util.Objects;

/**
 * Immutable detail for a single subsystem, returned inside {@link SubsystemSnapshot}.
 *
 * @param name      the subsystem name
 * @param phase     the bootstrap phase name (e.g. {@code "FOUNDATION"}, {@code "SERVICES"}, {@code "RUNTIME"})
 * @param dependsOn names of subsystems this one depends on; defensively copied, never {@code null}
 * @param running   whether the subsystem is currently running
 * @param optional  whether the subsystem is optional (skippable under a DEGRADE failure policy)
 * @since 0.9
 */
public record SubsystemDescriptor(
        String name,
        String phase,
        List<String> dependsOn,
        boolean running,
        boolean optional) {

    public SubsystemDescriptor {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(phase, "phase");
        dependsOn = List.copyOf(dependsOn);
    }
}
