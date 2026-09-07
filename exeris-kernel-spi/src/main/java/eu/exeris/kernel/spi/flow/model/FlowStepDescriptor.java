/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.spi.flow.model;

/**
 * One step of a {@link FlowDefinition}: where it sits, what identifies it, what it does, and what
 * undoes it.
 *
 * @param stepId       the step's <b>position</b> in the definition (0-based) and its slab slot
 *                     address. Not an identity: it changes when steps are reordered, which is
 *                     exactly why {@link #name()} exists (ADR-062)
 * @param name         the step's <b>identity</b> — distinct within a definition (enforced by
 *                     {@link FlowDefinition}) and stable across redeployments. A parked saga
 *                     records it, and resume refuses to continue if the plan disagrees. Also
 *                     what JFR and diagnostics display
 * @param action       the action to execute; must not be {@code null}
 * @param compensation the compensation action for backward recovery; {@code null} if not defined
 *
 * @implNote All components are primitives or effectively immutable references and the record
 *           performs no identity operation ({@code ==}, {@code synchronized},
 *           {@code identityHashCode}), so C2 can scalarise it via escape analysis and it is ready
 *           for {@code value record} when JEP 401 is stable. In the Enterprise tier {@code action}
 *           and {@code compensation} live as dispatch-table indices rather than raw addresses in the
 *           step-registry slab, and {@code stepId} addresses the slot directly:
 *           {@code address = stepSlabBase + stepId * STEP_DESCRIPTOR_STRIDE}.
 * @since 0.5
 * @see FlowStepAction
 * @see FlowDefinition
 */
public record FlowStepDescriptor(
        int            stepId,
        String         name,
        FlowStepAction action,
        FlowStepAction compensation
) {

    /**
     * Validates the components a step cannot do without, so an unusable descriptor never reaches a
     * definition.
     *
     * @throws IllegalArgumentException if {@code stepId} is negative, if {@code name} is
     *                                  {@code null} or blank, or if {@code action} is {@code null}
     * @apiNote {@code compensation} is the one component allowed to be {@code null}: a step with
     *          nothing to undo declares none. Use {@link #hasCompensation()} rather than testing it.
     */
    public FlowStepDescriptor {
        if (stepId < 0) {
            throw new IllegalArgumentException("Step stepId must be >= 0, got: " + stepId);
        }
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Step name must not be null or blank");
        }
        if (action == null) {
            throw new IllegalArgumentException("Step action must not be null (stepId: " + stepId + ")");
        }
        // compensation is intentionally nullable — not all steps define rollback logic
    }

    /**
     * Reports whether this step declares anything to undo it on the rollback path.
     *
     * @return {@code true} when {@link #compensation()} is non-{@code null}
     */
    public boolean hasCompensation() {
        return compensation != null;
    }
}

