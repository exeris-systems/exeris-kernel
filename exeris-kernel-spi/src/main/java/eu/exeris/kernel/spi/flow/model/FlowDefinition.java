/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
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
 * @param name                  unique flow definition name (used as a key in the registry)
 * @param version               version of this definition, {@code >=} {@link #INITIAL_VERSION}.
 *                              Several versions of one name may be registered at once, and a parked
 *                              saga resumes on the version it recorded rather than on whichever is
 *                              newest (ADR-064)
 * @param steps                 ordered list of step descriptors; must not be empty, and step names
 *                              must be distinct — a parked saga records the name it stopped at, and
 *                              two steps sharing one would let resume bind to the wrong step while
 *                              the identity check still passes
 * @param timeoutDurationNanos  default duration limit for instances compiled from this definition;
 *                              scheduler computes the absolute deadline as
 *                              {@code System.nanoTime() + timeoutDurationNanos}
 * @param maxRetries            maximum number of step-level retries before triggering compensation
 *
 * @implNote The {@code steps} list is always an immutable {@link List#copyOf} snapshot, the
 *           {@code String name} field is read on the bootstrap path rather than the hot path, and
 *           the record performs no identity operations — so it is ready for {@code value record}
 *           when JEP 401 is stable.
 * @since 0.5
 * @see FlowStepDescriptor
 * @see FlowExecutionPlan
 */
public record FlowDefinition(
        String                    name,
        int                       version,
        List<FlowStepDescriptor>  steps,
        long                      timeoutDurationNanos,
        int                       maxRetries
) {

    /** The version a definition carries when its author declared none. */
    public static final int INITIAL_VERSION = 1;

    /**
     * Validates every invariant eagerly and replaces {@code steps} with an immutable copy, so a
     * definition cannot be mutated through the list its author passed in.
     *
     * @throws NullPointerException     if {@code name} or {@code steps} is {@code null}
     * @throws IllegalArgumentException if {@code name} is blank; if {@code version} is below
     *                                  {@link #INITIAL_VERSION}; if {@code steps} is empty or holds
     *                                  two steps with the same {@link FlowStepDescriptor#name()};
     *                                  if {@code timeoutDurationNanos} is not positive; or if
     *                                  {@code maxRetries} is negative
     */
    public FlowDefinition {
        Objects.requireNonNull(name, "flow definition name must not be null");
        if (name.isBlank()) {
            throw new IllegalArgumentException("flow definition name must not be blank");
        }
        if (version < INITIAL_VERSION) {
            // Versions start at 1 so that 0 can mean "this snapshot predates versioning" on the
            // resume path without ever colliding with a real version (ADR-064).
            throw new IllegalArgumentException(
                    "flow definition version must be >= " + INITIAL_VERSION + ", got: " + version);
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
     * Builds a definition whose author declared no version, defaulting it to
     * {@link #INITIAL_VERSION}.
     *
     * <p>Unlike {@code FlowSnapshot}'s compatibility constructors, this one is <em>not</em> a route
     * to a fail-closed outcome: a definition without a declared version is a perfectly valid
     * definition, and an application whose flows never carry one runs unaffected by versioning.
     * Versioning is a choice the application makes, not an obligation it inherits.
     *
     * @param name                 unique flow definition name
     * @param steps                ordered, non-empty step descriptors with distinct names
     * @param timeoutDurationNanos default duration limit in nanoseconds; must be positive
     * @param maxRetries           step-level retries before compensation is triggered; {@code >= 0}
     * @throws NullPointerException     if {@code name} or {@code steps} is {@code null}
     * @throws IllegalArgumentException under the same conditions as the canonical constructor
     * @since 0.11
     */
    public FlowDefinition(String name,
                          List<FlowStepDescriptor> steps,
                          long timeoutDurationNanos,
                          int maxRetries) {
        this(name, INITIAL_VERSION, steps, timeoutDurationNanos, maxRetries);
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

