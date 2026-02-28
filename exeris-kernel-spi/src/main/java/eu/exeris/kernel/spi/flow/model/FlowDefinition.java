/*
 * Copyright (C) 2025-2026 Exeris. All rights reserved.
 *
 * This code is part of the Exeris Systems.
 * Distributed under the proprietary Exeris Software License.
 * Unauthorized copying or distribution is prohibited.
 */
package eu.exeris.kernel.spi.flow.model;

import java.util.List;
import java.util.Objects;

/**
 * Immutable definition of a named flow — the blueprint from which executable
 * {@link FlowExecutionPlan} instances are compiled.
 *
 * <h2>Valhalla Readiness</h2>
 * <p>The {@code steps} list is always an immutable {@link List#copyOf} snapshot.
 * The {@code String name} field is only on the bootstrap path, not the hot path.
 * No identity operations. Ready for {@code value record} when JEP 401 is stable.
 *
 * @param name                  unique flow definition name (used as a key in the registry)
 * @param steps                 ordered list of step descriptors; must not be empty
 * @param timeoutDurationNanos  default duration limit for instances compiled from this definition;
 *                              scheduler computes the absolute deadline as
 *                              {@code System.nanoTime() + timeoutDurationNanos}
 * @param maxRetries            maximum number of step-level retries before triggering compensation
 *
 * @since 0.5.0
 * @see FlowStepDescriptor
 * @see FlowExecutionPlan
 */
public record FlowDefinition(
        String                    name,
        List<FlowStepDescriptor>  steps,
        long                      timeoutDurationNanos,
        int                       maxRetries
) {

    /** Compact constructor — validates invariants and defensively copies the step list. */
    public FlowDefinition {
        Objects.requireNonNull(name, "flow definition name must not be null");
        if (name.isBlank()) {
            throw new IllegalArgumentException("flow definition name must not be blank");
        }
        Objects.requireNonNull(steps, "steps list must not be null");
        if (steps.isEmpty()) {
            throw new IllegalArgumentException("flow definition must have at least one step");
        }
        if (timeoutDurationNanos <= 0) {
            throw new IllegalArgumentException("timeoutDurationNanos must be > 0, got: " + timeoutDurationNanos);
        }
        if (maxRetries < 0) {
            throw new IllegalArgumentException("maxRetries must be >= 0, got: " + maxRetries);
        }
        steps = List.copyOf(steps);  // defensive copy — immutable
    }
}

