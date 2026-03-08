/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.spi.flow.model;

/**
 * Descriptor for a directed transition between two steps in a {@link FlowDefinition}.
 *
 * <h2>Valhalla Readiness</h2>
 * <p>All fields are primitives or {@code String} — no identity operations.
 * JIT can scalarise on hot paths via Escape Analysis.
 * Ready for {@code value record} when JEP 401 is stable.
 *
 * <h2>Enterprise Off-Heap Mapping</h2>
 * <p>In the Enterprise tier, transitions are stored in a flat slab array with adjacency
 * indexed by {@code fromStep}. The {@code conditionTag} is stored as an FNV-1a hash
 * (long) in the slab to avoid heap {@code String} references on the hot lookup path.
 *
 * @param fromStep     the source step id (must reference a registered step)
 * @param toStep       the target step id (must reference a registered step)
 * @param conditionTag a semantic string tag identifying the routing condition;
 *                     use {@code "default"} for unconditional transitions;
 *                     never {@code null}
 *
 * @since 0.5.0
 * @see FlowStepDescriptor
 * @see FlowDefinition
 */
public record FlowTransitionDescriptor(
        int    fromStep,
        int    toStep,
        String conditionTag
) {

    /** Compact constructor — validates required fields. */
    public FlowTransitionDescriptor {
        if (fromStep < 0) {
            throw new IllegalArgumentException("fromStep must be >= 0, got: " + fromStep);
        }
        if (toStep < 0) {
            throw new IllegalArgumentException("toStep must be >= 0, got: " + toStep);
        }
        if (conditionTag == null || conditionTag.isBlank()) {
            throw new IllegalArgumentException(
                    "conditionTag must not be null or blank; use \"default\" for unconditional");
        }
    }

    /** Convenience factory for an unconditional (default) transition. */
    public static FlowTransitionDescriptor unconditional(int fromStep, int toStep) {
        return new FlowTransitionDescriptor(fromStep, toStep, "default");
    }
}

