/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.spi.flow.model;

/**
 * Descriptor for a single executable step within a {@link FlowDefinition}.
 *
 * <h2>Valhalla Readiness</h2>
 * <p>Immutable record — all fields are primitives or effectively immutable references.
 * No identity operations ({@code ==}, {@code synchronized}, {@code identityHashCode}).
 * JIT C2 can scalarise this record on hot paths via Escape Analysis.
 * Ready for {@code value record} migration when JEP 401 is stable.
 *
 * <h2>Enterprise Off-Heap Mapping</h2>
 * <p>In the Enterprise tier, the {@code action} and {@code compensation} references are
 * stored as indices into a dispatch table (not raw addresses) within the step registry slab.
 * The {@code stepId} field maps directly to the slab slot:
 * {@code address = stepSlabBase + stepId * STEP_DESCRIPTOR_STRIDE}.
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

    /** Compact constructor — validates required fields. */
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

    /** Returns {@code true} if this step has a backward compensation action defined. */
    public boolean hasCompensation() {
        return compensation != null;
    }
}

