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
 * Immutable descriptor of a single resolved capability in the composition graph (ADR-024).
 *
 * @param name     the capability / subsystem name it is keyed by
 * @param provides the capability names this node provides; defensively copied, never {@code null}
 * @param requires the capability names this node requires; defensively copied, never {@code null}
 * @param optional whether the capability is optional (may be skipped under a DEGRADE failure policy)
 * @since 0.9.0
 */
public record CapabilityDescriptor(
        String name,
        List<String> provides,
        List<String> requires,
        boolean optional) {

    public CapabilityDescriptor {
        Objects.requireNonNull(name, "name");
        provides = List.copyOf(provides);
        requires = List.copyOf(requires);
    }
}
