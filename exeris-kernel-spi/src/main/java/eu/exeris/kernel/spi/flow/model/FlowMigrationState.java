/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.spi.flow.model;

import java.util.Arrays;
import java.util.Objects;

/**
 * The part of a parked saga a {@link FlowDefinitionMigration} may rewrite (ADR-064).
 *
 * <h2>What is deliberately absent</h2>
 * <p>Instance identity, {@link FlowState}, the definition name, the definition version, the OCC
 * {@code schemaVersion} and the timeout are <b>not</b> here. A transform decides where a saga lands
 * inside the next definition; it does not get to decide which saga it is, whether it is still
 * running, or who wins a concurrent write.
 *
 * <h2>{@code parkedStep} is where the saga parked, not where it resumes</h2>
 * <p>The distinction is load-bearing and cost ADR-064 an amendment: {@code wake()} resumes at
 * {@code parkedStep + 1}. A transform that returns the intended <em>resume</em> step makes the
 * runtime advance past it, skipping a step — and ADR-062's identity check passes, because the
 * emitted index and name agree with each other. Return the step the saga should be considered to
 * have completed under the new definition.
 *
 * @param parkedStep        zero-based index of the step the saga parked at, in the target definition
 * @param parkedStepName    identity of that step; validated against the target plan after the
 *                          transform returns, never trusted
 * @param compensationStack step indices whose compensations run in reverse order, in target-definition
 *                          terms — carrying these across a version boundary unchanged compensates the
 *                          wrong steps on failure
 * @param stackPointer      number of valid entries in {@code compensationStack}
 * @param opaqueState       implementation-specific payload; kernel-opaque, and the only component
 *                          here that is user data rather than definition metadata
 * @since 0.11.0
 */
public record FlowMigrationState(
        int    parkedStep,
        String parkedStepName,
        int[]  compensationStack,
        int    stackPointer,
        byte[] opaqueState
) {

    /** Compact constructor — validates and defensively copies, like {@link FlowSnapshot}. */
    public FlowMigrationState {
        Objects.requireNonNull(parkedStepName, "parkedStepName must not be null");
        Objects.requireNonNull(compensationStack, "compensationStack must not be null — use new int[0]");
        Objects.requireNonNull(opaqueState, "opaqueState must not be null — use new byte[0]");
        if (parkedStepName.isBlank()) {
            throw new IllegalArgumentException("parkedStepName must not be blank");
        }
        if (parkedStep < 0) {
            throw new IllegalArgumentException("parkedStep must be >= 0, got: " + parkedStep);
        }
        if (stackPointer < 0 || stackPointer > compensationStack.length) {
            throw new IllegalArgumentException(
                    "stackPointer out of bounds: " + stackPointer
                    + " (compensationStack.length=" + compensationStack.length + ')');
        }
        compensationStack = Arrays.copyOf(compensationStack, compensationStack.length);
        opaqueState = Arrays.copyOf(opaqueState, opaqueState.length);
    }

    /** Defensive copy — callers must not mutate the result. */
    @Override
    public int[] compensationStack() {
        return Arrays.copyOf(compensationStack, compensationStack.length);
    }

    /** Defensive copy — callers must not mutate the result. */
    @Override
    public byte[] opaqueState() {
        return Arrays.copyOf(opaqueState, opaqueState.length);
    }

    /**
     * Value equality over array <em>contents</em>.
     *
     * <p>A record's generated {@code equals} compares array components by reference, so two carriers
     * describing the same parked saga would be unequal. That is an identity-sensitive operation on a
     * carrier, which the Valhalla-readiness rule exists to keep out — and here it would also make the
     * obvious way to assert a transform is a no-op quietly always false. Mirrors {@link FlowSnapshot}.
     */
    @Override
    public boolean equals(Object other) {
        return this == other
                || other instanceof FlowMigrationState state
                && parkedStep == state.parkedStep
                && stackPointer == state.stackPointer
                && parkedStepName.equals(state.parkedStepName)
                && Arrays.equals(compensationStack, state.compensationStack)
                && Arrays.equals(opaqueState, state.opaqueState);
    }

    @Override
    public int hashCode() {
        int result = Integer.hashCode(parkedStep);
        result = 31 * result + parkedStepName.hashCode();
        result = 31 * result + Arrays.hashCode(compensationStack);
        result = 31 * result + stackPointer;
        return 31 * result + Arrays.hashCode(opaqueState);
    }

    /** Sizes rather than contents: {@code opaqueState} is user data and never rendered. */
    @Override
    public String toString() {
        return "FlowMigrationState[parkedStep=" + parkedStep
                + ", parkedStepName=" + parkedStepName
                + ", compensationStack=" + Arrays.toString(compensationStack)
                + ", stackPointer=" + stackPointer
                + ", opaqueState=" + opaqueState.length + " bytes]";
    }
}
