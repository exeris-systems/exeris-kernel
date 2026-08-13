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
 * No identity operations.
 * Declared {@code value record} on the `preview` line (JEP 401); the distributed line compiles
 * the same source as an identity {@code record}, and the modifier is asserted by
 * {@code Class::isValue} in the module's value-carrier registry test.
 *
 * @param name                  unique flow definition name (used as a key in the registry)
 * @param steps                 ordered list of step descriptors; must not be empty, and step names
 *                              must be distinct — a parked saga records the name it stopped at, and
 *                              two steps sharing one would let resume bind to the wrong step while
 *                              the identity check still passes
 * @param timeoutDurationNanos  default duration limit for instances compiled from this definition;
 *                              scheduler computes the absolute deadline as
 *                              {@code System.nanoTime() + timeoutDurationNanos}
 * @param maxRetries            maximum number of step-level retries before triggering compensation
 *
 * @since 0.5.0
 * @see FlowStepDescriptor
 * @see FlowExecutionPlan
 */
public value record FlowDefinition(
        String                    name,
        int                       version,
        List<FlowStepDescriptor>  steps,
        long                      timeoutDurationNanos,
        int                       maxRetries
) {

    /** The version a definition carries when its author declared none. */
    public static final int INITIAL_VERSION = 1;

    /** Compact constructor — validates invariants and defensively copies the step list. */
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
     * Convenience constructor for a definition whose author declared no version, defaulting it to
     * {@link #INITIAL_VERSION}.
     *
     * <p>Unlike {@code FlowSnapshot}'s pre-0.11 shim, this one is <em>not</em> a route to a
     * fail-closed outcome: a definition without a declared version is a perfectly valid definition,
     * and an application that never versions its flows keeps the behaviour it had before ADR-064.
     * Versioning is a choice the application makes, not an obligation it inherits.
     *
     * @since 0.11.0
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

