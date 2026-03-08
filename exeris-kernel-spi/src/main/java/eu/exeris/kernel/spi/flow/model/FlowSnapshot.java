/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
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
 * @param definitionName    name of the {@link FlowDefinition} this instance was compiled from;
 *                          must not be {@code null} or blank
 * @param currentStep       zero-based index of the step to resume execution at;
 *                          must be {@code >= 0}
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
     * Maximum allowed size of the {@code opaqueState} payload (916 bytes), matching the
     * off-heap context slab reserved region documented in {@code FLOW_CONTEXT_STRIDE}.
     */
    public static final int MAX_OPAQUE_STATE_BYTES = 916;

    /**
     * Compact constructor — validates all invariants eagerly (fail-fast) and defensively
     * copies both mutable array components to guarantee true immutability.
     *
     * <h2>Null Policy</h2>
     * <p>{@code compensationStack} and {@code opaqueState} must not be {@code null}.
     * Pass empty arrays ({@code new int[0]}, {@code new byte[0]}) when the data is absent.
     *
     * <h2>Bounds Validation</h2>
     * <ul>
     *   <li>{@code definitionName} must not be blank.</li>
     *   <li>{@code currentStep} must be {@code >= 0} (zero-based step index).</li>
     *   <li>{@code stackPointer} must be in {@code [0, compensationStack.length]}.</li>
     *   <li>{@code opaqueState.length} must not exceed {@link #MAX_OPAQUE_STATE_BYTES}.</li>
     * </ul>
     *
     * <p>This is the <em>cold</em> construction path (snapshot creation on PARK / eviction),
     * so the allocation cost is acceptable.
     */
    public FlowSnapshot {
        Objects.requireNonNull(definitionName,    "definitionName must not be null");
        Objects.requireNonNull(state,             "state must not be null");
        Objects.requireNonNull(lastUpdate,        "lastUpdate must not be null");
        Objects.requireNonNull(timeout,           "timeout must not be null");
        Objects.requireNonNull(compensationStack, "compensationStack must not be null — use new int[0] for empty");
        Objects.requireNonNull(opaqueState,       "opaqueState must not be null — use new byte[0] for empty");
        if (definitionName.isBlank()) {
            throw new IllegalArgumentException("definitionName must not be blank");
        }
        if (currentStep < 0) {
            throw new IllegalArgumentException(
                    "currentStep must be >= 0 (zero-based step index), got: " + currentStep);
        }
        if (stackPointer < 0 || stackPointer > compensationStack.length) {
            throw new IllegalArgumentException(
                    "stackPointer out of bounds: " + stackPointer
                    + " (compensationStack.length=" + compensationStack.length + ')');
        }
        if (opaqueState.length > MAX_OPAQUE_STATE_BYTES) {
            throw new IllegalArgumentException(
                    "opaqueState exceeds max size: " + opaqueState.length
                    + " > " + MAX_OPAQUE_STATE_BYTES);
        }
        compensationStack = Arrays.copyOf(compensationStack, compensationStack.length);
        opaqueState       = Arrays.copyOf(opaqueState, opaqueState.length);
    }

    /**
     * Returns a <em>defensive copy</em> of the compensation stack array.
     *
     * <p>Callers must not modify the returned array. A copy is returned to preserve
     * the immutability guarantee documented in the compact constructor.
     * This accessor is on the cold persistence path; the allocation cost is acceptable.
     */
    public int[] compensationStack() {
        return Arrays.copyOf(compensationStack, compensationStack.length);
    }

    /**
     * Returns a <em>defensive copy</em> of the opaque state byte array.
     *
     * <p>Callers must not modify the returned array. A copy is returned to preserve
     * the immutability guarantee documented in the compact constructor.
     * This accessor is on the cold persistence path; the allocation cost is acceptable.
     */
    public byte[] opaqueState() {
        return Arrays.copyOf(opaqueState, opaqueState.length);
    }

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
     *
     * <p><b>Allocation-free:</b> uses a simple type-pattern bind ({@code instanceof
     * FlowSnapshot other}) and accesses {@code other.compensationStack} /
     * {@code other.opaqueState} as fields directly, bypassing the defensive-copy
     * accessors. A record deconstruction pattern ({@code instanceof FlowSnapshot(...)})
     * would invoke those accessors and allocate two unnecessary array copies per call.
     */
    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (!(obj instanceof FlowSnapshot other)) {
            return false;
        }
        return instanceIdMost   == other.instanceIdMost
               && instanceIdLeast  == other.instanceIdLeast
               && currentStep      == other.currentStep
               && stackPointer     == other.stackPointer
               && state            == other.state
               && Objects.equals(definitionName, other.definitionName)
               && Objects.equals(lastUpdate,     other.lastUpdate)
               && Objects.equals(timeout,        other.timeout)
               && Arrays.equals(compensationStack, other.compensationStack)
               && Arrays.equals(opaqueState,       other.opaqueState);
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

