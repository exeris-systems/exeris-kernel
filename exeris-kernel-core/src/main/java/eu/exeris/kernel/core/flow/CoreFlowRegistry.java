/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.core.flow;

import eu.exeris.kernel.spi.exceptions.flow.FlowRegistryException;
import eu.exeris.kernel.spi.flow.FlowRegistry;
import eu.exeris.kernel.spi.flow.model.FlowStepDescriptor;
import eu.exeris.kernel.spi.flow.model.FlowTransitionDescriptor;

import java.util.Arrays;
import java.util.concurrent.atomic.AtomicReference;

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

    @SuppressWarnings("PMD.UseVarargs")
    public synchronized void replace(FlowStepDescriptor[] newSteps, FlowTransitionDescriptor[][] newTransitions) {
        snapshotRef.set(new RegistrySnapshot(
                Arrays.copyOf(newSteps, newSteps.length),
                Arrays.copyOf(newTransitions, newTransitions.length)
        ));
    }

    private value record RegistrySnapshot(FlowStepDescriptor[] steps, FlowTransitionDescriptor[][] transitions) {
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
