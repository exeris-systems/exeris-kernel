/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.spi.flow.model;

/**
 * A directed edge between two steps of a {@link FlowDefinition}, tagged with the condition under
 * which routing takes it.
 *
 * @param fromStep     the source step id (must reference a registered step)
 * @param toStep       the target step id (must reference a registered step)
 * @param conditionTag a semantic string tag identifying the routing condition;
 *                     use {@code "default"} for unconditional transitions;
 *                     never {@code null}
 *
 * @implNote All components are primitives or {@code String} and the record performs no identity
 *           operation, so C2 can scalarise it via escape analysis and it is ready for
 *           {@code value record} when JEP 401 is stable. In the Enterprise tier transitions live in
 *           a flat slab array with adjacency indexed by {@code fromStep}, and {@code conditionTag}
 *           is stored as an FNV-1a hash ({@code long}) so the hot lookup path holds no heap
 *           {@code String}.
 * @since 0.5
 * @see FlowStepDescriptor
 * @see FlowDefinition
 */
public record FlowTransitionDescriptor(
        int    fromStep,
        int    toStep,
        String conditionTag
) {

    /**
     * Validates that both endpoints are addressable and that the condition is nameable, so an edge
     * that could never be resolved never reaches a definition.
     *
     * @throws IllegalArgumentException if {@code fromStep} or {@code toStep} is negative, or if
     *                                  {@code conditionTag} is {@code null} or blank
     */
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

    /**
     * Builds an edge that routing always takes, by tagging it {@code "default"} — the conventional
     * name for "no condition", since {@code conditionTag} may not be blank.
     *
     * @param fromStep the source step id; must not be negative
     * @param toStep   the target step id; must not be negative
     * @return an unconditional transition between the two steps
     * @throws IllegalArgumentException if either step id is negative
     */
    public static FlowTransitionDescriptor unconditional(int fromStep, int toStep) {
        return new FlowTransitionDescriptor(fromStep, toStep, "default");
    }
}

