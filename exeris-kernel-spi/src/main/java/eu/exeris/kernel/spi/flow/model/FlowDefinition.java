/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.spi.flow.model;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
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
        requireDistinctStepNames(steps);
    }

    /**
     * Rejects a definition whose steps do not have distinct names (ADR-062).
     *
     * <p>Step names identify a step across redeployments — a parked saga records the name it stopped
     * at, and resume refuses to continue if the plan disagrees. Two steps sharing a name would make
     * that comparison pass while binding to the wrong one, which is the failure the identity check
     * exists to prevent. So uniqueness is enforced where definitions are built rather than assumed at
     * the point it matters.
     *
     * @param steps the already-copied step list
     */
    private static void requireDistinctStepNames(List<FlowStepDescriptor> steps) {
        Set<String> seen = HashSet.newHashSet(steps.size());
        for (FlowStepDescriptor step : steps) {
            if (!seen.add(step.name())) {
                throw new IllegalArgumentException(
                        "flow definition step names must be distinct — duplicate: " + step.name());
            }
        }
    }
}

