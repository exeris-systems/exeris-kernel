/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.core.flow;

import eu.exeris.kernel.spi.exceptions.flow.FlowRegistryException;
import eu.exeris.kernel.spi.flow.FlowRegistry;
import eu.exeris.kernel.spi.flow.model.FlowStepDescriptor;
import eu.exeris.kernel.spi.flow.model.FlowTransitionDescriptor;

import java.util.Arrays;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Heap-backed {@link FlowRegistry}: holds the current step and transition descriptors behind a
 * single {@link AtomicReference} to an immutable {@link RegistrySnapshot}, so a lookup never blocks
 * a concurrent registration.
 *
 * <p>{@link #registerStep} and {@link #registerTransition} each copy the current snapshot's arrays
 * into a freshly sized replacement and swap it in; {@link #lookupStep} and
 * {@link #lookupTransitions} read the current snapshot without synchronizing. {@link #replace} is
 * the bulk path {@link CoreFlowPlanFactory#compile} uses after compiling a plan: it swaps in an
 * entirely new snapshot rather than growing the existing one.
 *
 * <p><b>Allocation:</b> every successful registration copies the current arrays into a freshly
 * sized array via {@code Arrays.copyOf}; nothing is pre-sized or pooled.
 * <p><b>Thread confinement:</b> any thread — registration and {@link #replace} serialize on
 * {@code this} via {@code synchronized}; lookups are lock-free reads of the current snapshot.
 * <p><b>Ownership:</b> each snapshot is immutable and replaced wholesale on every write; no caller
 * holds a reference across a registration and nothing needs to be released.
 */
@SuppressWarnings("PMD.PublicMemberInNonPublicType")
final class CoreFlowRegistry implements FlowRegistry {

    private final AtomicReference<RegistrySnapshot> snapshotRef =
            new AtomicReference<>(new RegistrySnapshot(new FlowStepDescriptor[0], new FlowTransitionDescriptor[0][]));

    @Override
    public synchronized void registerStep(FlowStepDescriptor step) {
        RegistrySnapshot snapshot = snapshotRef.get();
        FlowStepDescriptor[] steps = Arrays.copyOf(
                snapshot.steps(),
                Math.max(snapshot.steps().length, step.stepId() + 1)
        );
        if (steps[step.stepId()] != null) {
            throw FlowRegistryException.duplicateStep(step.stepId());
        }
        steps[step.stepId()] = step;
        snapshotRef.set(new RegistrySnapshot(steps, snapshot.transitions()));
    }

    @Override
    public synchronized void registerTransition(FlowTransitionDescriptor transition) {
        RegistrySnapshot snapshot = snapshotRef.get();
        FlowStepDescriptor[] steps = snapshot.steps();
        if (transition.fromStep() >= steps.length || steps[transition.fromStep()] == null) {
            throw FlowRegistryException.stepNotFound(transition.fromStep());
        }
        if (transition.toStep() >= steps.length || steps[transition.toStep()] == null) {
            throw FlowRegistryException.stepNotFound(transition.toStep());
        }

        FlowTransitionDescriptor[][] transitions = Arrays.copyOf(
                snapshot.transitions(),
                Math.max(snapshot.transitions().length, transition.fromStep() + 1)
        );
        FlowTransitionDescriptor[] existing = transitions[transition.fromStep()];
        if (existing == null) {
            transitions[transition.fromStep()] = new FlowTransitionDescriptor[]{transition};
        } else {
            FlowTransitionDescriptor[] updated = Arrays.copyOf(existing, existing.length + 1);
            updated[existing.length] = transition;
            transitions[transition.fromStep()] = updated;
        }
        snapshotRef.set(new RegistrySnapshot(steps, transitions));
    }

    @Override
    public FlowStepDescriptor lookupStep(int stepId) {
        FlowStepDescriptor[] steps = snapshotRef.get().steps();
        if (stepId < 0 || stepId >= steps.length || steps[stepId] == null) {
            throw FlowRegistryException.stepNotFound(stepId);
        }
        return steps[stepId];
    }

    @Override
    public FlowTransitionDescriptor[] lookupTransitions(int fromStep) {
        FlowTransitionDescriptor[][] transitions = snapshotRef.get().transitions();
        if (fromStep < 0 || fromStep >= transitions.length || transitions[fromStep] == null) {
            return new FlowTransitionDescriptor[0];
        }
        return transitions[fromStep];
    }

    /**
     * Replaces the entire current snapshot with {@code newSteps} and {@code newTransitions} in one
     * atomic swap, rather than growing the existing arrays entry by entry.
     *
     * <p>Called by {@link CoreFlowPlanFactory#compile} after a successful compilation, so the
     * registry always reflects the most recently compiled plan's step and transition descriptors.
     *
     * @param newSteps       the complete step-descriptor array to install; copied, not aliased
     * @param newTransitions the complete transition adjacency table to install; copied, not aliased
     */
    @SuppressWarnings("PMD.UseVarargs")
    public synchronized void replace(FlowStepDescriptor[] newSteps, FlowTransitionDescriptor[][] newTransitions) {
        snapshotRef.set(new RegistrySnapshot(
                Arrays.copyOf(newSteps, newSteps.length),
                Arrays.copyOf(newTransitions, newTransitions.length)
        ));
    }

    private record RegistrySnapshot(FlowStepDescriptor[] steps, FlowTransitionDescriptor[][] transitions) {
        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof RegistrySnapshot(var otherSteps, var otherTransitions))) {
                return false;
            }
            return Arrays.equals(steps, otherSteps) && Arrays.deepEquals(transitions, otherTransitions);
        }

        @Override
        public int hashCode() {
            return 31 * Arrays.hashCode(steps) + Arrays.deepHashCode(transitions);
        }

        @Override
        public String toString() {
            return "RegistrySnapshot[steps=" + Arrays.toString(steps)
                    + ", transitions=" + Arrays.deepToString(transitions) + "]";
        }
    }
}
