/*
 * Copyright (C) 2025-2026 Exeris. All rights reserved.
 *
 * This code is part of the Exeris Systems.
 * Distributed under the proprietary Exeris Software License.
 * Unauthorized copying or distribution is prohibited.
 */
package eu.exeris.kernel.spi.flow.model;

import java.time.Instant;
import java.util.Arrays;
import java.util.Objects;

/**
 * Immutable snapshot of a flow instance's persisted state.
 *
 * <h2>Persistence Contract</h2>
 * <p>Used by {@link FlowSnapshotStore} to persist and restore flow instances across
 * PARK/WAKE cycles and kernel restarts. Fields map 1:1 to the off-heap context
 * slab layout in the Enterprise tier (see {@code FLOW_CONTEXT_STRIDE}).
 *
 * <h2>Array Equality</h2>
 * <p>This record contains {@code int[]} and {@code byte[]} array fields.
 * The default record {@code equals()}/{@code hashCode()}/{@code toString()} use
 * reference equality for arrays, which is incorrect for structural comparison.
 * All three methods are overridden here to use {@link Arrays#equals} /
 * {@link Arrays#hashCode} / {@link Arrays#toString} for deep array semantics.
 *
 * <h2>Valhalla Readiness</h2>
 * <p>Mostly primitive fields. {@code Instant} and array fields are on the cold
 * persistence path only — not used in the hot dispatch loop. No identity operations.
 *
 * @param instanceIdMost    most-significant bits of the 128-bit flow instance UUID
 * @param instanceIdLeast   least-significant bits of the 128-bit flow instance UUID
 * @param definitionName    name of the {@link FlowDefinition} this instance was compiled from
 * @param currentStep       index of the step to resume execution at
 * @param state             current {@link FlowState}
 * @param lastUpdate        timestamp of the last state mutation (for LRU eviction ordering)
 * @param timeout           absolute expiry time of this flow instance
 * @param compensationStack array of step ids whose compensations must execute in reverse order
 * @param stackPointer      number of valid entries in {@code compensationStack}
 * @param opaqueState       raw byte payload for implementation-specific state (max 916 bytes)
 *
 * @since 0.5.0
 */
public record FlowSnapshot(
        long      instanceIdMost,
        long      instanceIdLeast,
        String    definitionName,
        int       currentStep,
        FlowState state,
        Instant   lastUpdate,
        Instant   timeout,
        int[]     compensationStack,
        int       stackPointer,
        byte[]    opaqueState
) {

    /**
     * Returns a combined hex string for JFR events and diagnostic logging.
     *
     * <p><b>Cold-path only</b> — this method allocates a {@code String}.
     * Do not call from the hot dispatch loop; use {@link #instanceIdMost()} and
     * {@link #instanceIdLeast()} directly for zero-allocation identity checks.
     */
    public String instanceId() {
        return Long.toHexString(instanceIdMost) + "-" + Long.toHexString(instanceIdLeast);
    }

    /**
     * Deep equality check — uses {@link Arrays#equals} for {@code compensationStack}
     * and {@code opaqueState} to avoid reference-equality false negatives.
     */
    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (!(obj instanceof FlowSnapshot(
                long idMost, long idLeast, String name, int step, FlowState state1, Instant update, Instant timeout1,
                int[] stack, int pointer, byte[] opaqueState1
        ))) {
            return false;
        }
        return instanceIdMost == idMost
               && instanceIdLeast == idLeast
               && currentStep == step
               && stackPointer == pointer
               && state == state1
               && Objects.equals(definitionName, name)
               && Objects.equals(lastUpdate, update)
               && Objects.equals(timeout, timeout1)
               && Arrays.equals(compensationStack, stack)
               && Arrays.equals(opaqueState, opaqueState1);
    }

    /**
     * Deep hash code — uses {@link Arrays#hashCode} for {@code compensationStack}
     * and {@code opaqueState}.
     */
    @Override
    public int hashCode() {
        int result = Objects.hash(
                instanceIdMost, instanceIdLeast, definitionName,
                currentStep, state, lastUpdate, timeout, stackPointer);
        result = 31 * result + Arrays.hashCode(compensationStack);
        result = 31 * result + Arrays.hashCode(opaqueState);
        return result;
    }

    /**
     * Human-readable representation — uses {@link Arrays#toString} for array fields
     * to produce meaningful diagnostic output instead of identity hash codes.
     */
    @Override
    public String toString() {
        return "FlowSnapshot["
               + "instanceId=" + instanceId()
               + ", definitionName=" + definitionName
               + ", currentStep=" + currentStep
               + ", state=" + state
               + ", lastUpdate=" + lastUpdate
               + ", timeout=" + timeout
               + ", compensationStack=" + Arrays.toString(compensationStack)
               + ", stackPointer=" + stackPointer
               + ", opaqueState=" + Arrays.toString(opaqueState)
               + ']';
    }
}

